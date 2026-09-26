package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.entity.ScheduledExecution;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.repository.ScheduledExecutionRepository;
import com.loadpilot.backend.service.execution.ScheduleNextRunCalculator;
import com.loadpilot.backend.service.execution.TriggerClaim;
import java.time.Instant;
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
 * PROTECTION ANTI DOUBLE-DECLENCHEMENT (P1-B, section 45 du prompt) :
 * verrou pessimiste (SELECT ... FOR UPDATE, voir
 * ScheduledExecutionRepository#findByIdForUpdate) sur la ligne
 * ScheduledExecution, ET reprogrammation immediate de "nextRunAt" AVANT
 * meme que l'Execution reelle ne soit creee/lancee (voir
 * ScheduledExecutionPoller#fireExecution, appele APRES le retour -donc
 * apres le commit- de cette methode). Une deuxieme tentative de
 * declenchement (poll tick suivant tres rapproche, ou un "run now"
 * simultane) trouve alors "nextRunAt" deja avance et se voit refusee
 * (TriggerClaim.skipped()) - EXACTEMENT le meme idiome deja etabli et
 * teste par ExecutionTransactionHelper.attemptCancellation pour eliminer
 * une course equivalente sur Execution. Jamais un simple booleen en
 * memoire (qui ne protegerait ni un redemarrage ni, plus tard, plusieurs
 * instances backend partageant la meme base).
 */
@Service
@RequiredArgsConstructor
public class ScheduledExecutionTransactionHelper {

    private final ScheduledExecutionRepository scheduledExecutionRepository;

    /** Appele par le poller (voir ScheduledExecutionPoller) pour CHAQUE id
     * retourne par findDueScheduleIds - revalide integralement l'etat SOUS
     * VERROU avant de decider quoi que ce soit (jamais une decision prise
     * depuis la lecture legere non verrouillee). */
    @Transactional
    public TriggerClaim claimForAutomaticTrigger(UUID scheduleId, Instant now) {
        return scheduledExecutionRepository.findByIdForUpdate(scheduleId)
                .filter(s -> s.isEnabled() && s.getNextRunAt() != null && !s.getNextRunAt().isAfter(now))
                .map(s -> doClaim(s, now))
                .orElseGet(TriggerClaim::skipped);
    }

    /** Appele par "run now" (voir ScheduledExecutionServiceImpl#runNow) -
     * ignore volontairement enabled/nextRunAt (un declenchement manuel est
     * toujours honore immediatement), mais prend le MEME verrou : un
     * declenchement automatique concurrent sur cette meme ligne attend
     * (ou est deja passe, auquel cas nextRunAt est deja reprogramme et ce
     * "run now" reclame quand meme un declenchement supplementaire reel et
     * distinct - un "run now" explicite n'est jamais silencieusement
     * fusionne avec un declenchement automatique concurrent). */
    @Transactional
    public TriggerClaim claimForManualRun(UUID scheduleId) {
        ScheduledExecution schedule = scheduledExecutionRepository.findByIdForUpdate(scheduleId)
                .orElseThrow(() -> new ResourceNotFoundException("Planification introuvable : " + scheduleId));
        return doClaim(schedule, Instant.now());
    }

    private TriggerClaim doClaim(ScheduledExecution schedule, Instant now) {
        schedule.setLastTriggeredAt(now);
        schedule.setNextRunAt(ScheduleNextRunCalculator.computeAfterTrigger(schedule, now));
        ScheduledExecution saved = scheduledExecutionRepository.saveAndFlush(schedule);
        return TriggerClaim.claimed(saved.getId(), saved.getScenario().getId(), saved.getCreatedBy().getId(), saved.getName());
    }

    /** Enregistre le RESULTAT REEL d'un declenchement deja reclame (voir
     * ScheduledExecutionPoller#fireExecution) - transaction courte separee,
     * APRES l'appel a ExecutionService (potentiellement lent) : jamais de
     * connexion DB retenue pendant ce travail (meme regle que
     * ExecutionTransactionHelper). */
    @Transactional
    public void recordTriggerOutcome(UUID scheduleId, UUID executionId, String error) {
        scheduledExecutionRepository.findById(scheduleId).ifPresent(s -> {
            s.setLastExecutionId(executionId);
            s.setLastTriggerError(error);
            scheduledExecutionRepository.save(s);
        });
    }
}
