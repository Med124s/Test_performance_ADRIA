package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.entity.Scenario;
import com.loadpilot.backend.entity.ScheduledExecution;
import com.loadpilot.backend.entity.ScheduledRunEvent;
import com.loadpilot.backend.enums.ScenarioStatus;
import com.loadpilot.backend.enums.ScheduleType;
import com.loadpilot.backend.enums.ScheduledRunOutcome;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.repository.ScheduledExecutionRepository;
import com.loadpilot.backend.repository.ScheduledRunEventRepository;
import com.loadpilot.backend.service.execution.ScheduleNextRunCalculator;
import com.loadpilot.backend.service.execution.TriggerClaim;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isole la SEULE portion transactionnelle courte et verrouillee de
 * ScheduledExecutionPoller/ScheduledExecutionServiceImpl#runNow - meme
 * principe exactement que ExecutionTransactionHelper (bean separe :
 * @Transactional est ignore sur un appel this.methode() depuis la meme
 * classe, voir sa Javadoc).
 *
 * PROTECTION ANTI DOUBLE-DECLENCHEMENT (P1-B) : verrou pessimiste
 * (SELECT ... FOR UPDATE, voir ScheduledExecutionRepository#findByIdForUpdate)
 * sur la ligne ScheduledExecution, ET reprogrammation immediate de
 * "nextRunAt" AVANT meme que l'Execution reelle ne soit creee/lancee. Une
 * deuxieme tentative de declenchement (poll tick suivant tres rapproche, ou
 * un "run now" simultane) trouve alors "nextRunAt" deja avance et se voit
 * refusee (TriggerClaim.skipped()). Jamais un simple booleen en memoire.
 *
 * LANCEMENTS MANQUES : si l'heure prevue est depassee de plus de
 * "missedGrace" (LoadPilot arrete a ce moment-la), rien n'est lance en
 * rattrapage : chaque occurrence manquee (au plus MAX_MISSED_EVENTS) est
 * enregistree comme MISSED, puis la prochaine occurrence est calculee
 * normalement a partir de maintenant.
 */
@Service
@RequiredArgsConstructor
public class ScheduledExecutionTransactionHelper {

    static final int MAX_MISSED_EVENTS = 20;
    static final String MISSED_REASON = "Manquée : LoadPilot était arrêté ou en veille à l'heure prévue.";

    private final ScheduledExecutionRepository scheduledExecutionRepository;
    private final ScheduledRunEventRepository runEventRepository;

    /**
     * Revalide integralement l'etat SOUS verrou (la liste retournee par findDueScheduleIds a pu
     * changer entre-temps) avant de reclamer le declenchement.
     */
    @Transactional
    public TriggerClaim claimForAutomaticTrigger(UUID scheduleId, Instant now, Duration missedGrace) {
        return scheduledExecutionRepository.findByIdForUpdate(scheduleId)
                .filter(s -> s.isEnabled() && s.getNextRunAt() != null && !s.getNextRunAt().isAfter(now))
                .map(s -> claimDue(s, now, missedGrace))
                .orElseGet(TriggerClaim::skipped);
    }

    @Transactional
    public TriggerClaim claimForAutomaticTrigger(UUID scheduleId, Instant now) {
        return claimForAutomaticTrigger(scheduleId, now, Duration.ofSeconds(120));
    }

    @Transactional
    public TriggerClaim claimForManualRun(UUID scheduleId) {
        ScheduledExecution schedule = scheduledExecutionRepository.findByIdForUpdate(scheduleId)
                .orElseThrow(() -> new ResourceNotFoundException("Planification introuvable : " + scheduleId));
        Instant now = Instant.now();
        return doClaim(schedule, now, now);
    }

    private TriggerClaim claimDue(ScheduledExecution schedule, Instant now, Duration missedGrace) {
        Instant planned = schedule.getNextRunAt();
        Scenario scenario = schedule.getScenario();
        if (scenario.getStatus() != ScenarioStatus.ACTIVE || scenario.isArchived() || scenario.getApplication().isArchived()) {
            // Suspendue (scenario indisponible) : jamais lancee ni rattrapee ; une recurrente repart de la
            // prochaine occurrence, une unique est enregistree comme non lancee.
            if (schedule.getScheduleType() == ScheduleType.ONE_TIME) {
                record(schedule.getId(), planned, now, ScheduledRunOutcome.FAILED,
                        "Non lancée : le scénario n'était pas disponible (désactivé ou archivé) à l'heure prévue.");
            }
            schedule.setNextRunAt(ScheduleNextRunCalculator.computeAfterTrigger(schedule, now));
            scheduledExecutionRepository.saveAndFlush(schedule);
            return TriggerClaim.skipped();
        }
        if (planned.isBefore(now.minus(missedGrace))) {
            List<Instant> missed = new ArrayList<>();
            missed.add(planned);
            missed.addAll(ScheduleNextRunCalculator.occurrencesBetween(schedule, planned, now, MAX_MISSED_EVENTS - 1));
            for (Instant at : missed) {
                record(schedule.getId(), at, now, ScheduledRunOutcome.MISSED, MISSED_REASON);
            }
            schedule.setNextRunAt(ScheduleNextRunCalculator.computeAfterTrigger(schedule, now));
            schedule.setLastTriggerError(MISSED_REASON);
            scheduledExecutionRepository.saveAndFlush(schedule);
            return TriggerClaim.missed(schedule.getId(), schedule.getCreatedBy().getId(), schedule.getName(), planned, missed.size());
        }
        return doClaim(schedule, now, planned);
    }

    private TriggerClaim doClaim(ScheduledExecution schedule, Instant now, Instant planned) {
        schedule.setLastTriggeredAt(now);
        schedule.setNextRunAt(ScheduleNextRunCalculator.computeAfterTrigger(schedule, now));
        ScheduledExecution saved = scheduledExecutionRepository.saveAndFlush(schedule);
        return TriggerClaim.claimed(saved.getId(), saved.getScenario().getId(), saved.getCreatedBy().getId(), saved.getName(), planned);
    }

    @Transactional
    public void recordTriggerOutcome(UUID scheduleId, UUID executionId, String error) {
        scheduledExecutionRepository.findById(scheduleId).ifPresent(s -> {
            if (executionId != null) s.setLastExecutionId(executionId);
            s.setLastTriggerError(error);
            scheduledExecutionRepository.save(s);
        });
    }

    /** Lancement prevu mais non fait (ignore, refuse), avec sa raison. */
    @Transactional
    public void recordSkipped(UUID scheduleId, Instant plannedAt, ScheduledRunOutcome outcome, String reason) {
        record(scheduleId, plannedAt, Instant.now(), outcome, reason);
        scheduledExecutionRepository.findById(scheduleId).ifPresent(s -> {
            s.setLastTriggerError(reason);
            scheduledExecutionRepository.save(s);
        });
    }

    private void record(UUID scheduleId, Instant plannedAt, Instant now, ScheduledRunOutcome outcome, String reason) {
        runEventRepository.save(ScheduledRunEvent.builder()
                .scheduleId(scheduleId)
                .plannedAt(plannedAt)
                .recordedAt(now)
                .outcome(outcome)
                .reason(reason.length() > 500 ? reason.substring(0, 500) : reason)
                .build());
    }
}
