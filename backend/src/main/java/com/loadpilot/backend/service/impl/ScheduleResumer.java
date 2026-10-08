package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.entity.Scenario;
import com.loadpilot.backend.entity.ScheduledExecution;
import com.loadpilot.backend.entity.ScheduledRunEvent;
import com.loadpilot.backend.enums.ScenarioStatus;
import com.loadpilot.backend.enums.ScheduleType;
import com.loadpilot.backend.enums.ScheduledRunOutcome;
import com.loadpilot.backend.repository.ScheduledRunEventRepository;
import com.loadpilot.backend.service.execution.ScheduleNextRunCalculator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reprise des planifications suspendues par leur scenario (desactive, archive, ou application archivee),
 * des que le scenario redevient utilisable : actif, non archive, application non archivee.
 *
 * Rien n'est rattrape : une recurrente repart de sa prochaine occurrence a partir de maintenant ; une
 * unique dont l'heure est passee pendant la suspension n'est jamais lancee (enregistree « Non lancée »
 * dans son historique, etat Terminee) - jamais un lancement surprise a la reactivation.
 */
@Component
@RequiredArgsConstructor
public class ScheduleResumer {

    static final String NOT_RUN_WHILE_SUSPENDED = "Non lancée : le scénario n'était pas disponible (désactivé ou archivé) à l'heure prévue.";

    /** Planifications reprises, et uniques dont l'heure est passee pendant la suspension. */
    public record Result(int resumed, int expired) {
    }

    private final ScheduledRunEventRepository runEventRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public static boolean usable(Scenario scenario) {
        return scenario.getStatus() == ScenarioStatus.ACTIVE && !scenario.isArchived() && !scenario.getApplication().isArchived();
    }

    /** Reprend les planifications suspendues du scenario s'il est utilisable ; sinon ne fait rien. */
    @Transactional
    public Result resume(Scenario scenario) {
        if (!usable(scenario)) return new Result(0, 0);
        List<ScheduledExecution> paused = entityManager.createQuery(
                        "SELECT se FROM ScheduledExecution se WHERE se.scenario.id = :id AND se.pausedByScenario = true",
                        ScheduledExecution.class)
                .setParameter("id", scenario.getId())
                .getResultList();
        Instant now = Instant.now();
        int resumed = 0;
        int expired = 0;
        for (ScheduledExecution schedule : paused) {
            schedule.setPausedByScenario(false);
            schedule.setEnabled(true);
            if (schedule.getScheduleType() == ScheduleType.ONE_TIME) {
                if (schedule.getRunAt() == null || !schedule.getRunAt().isAfter(now)) {
                    if (schedule.getNextRunAt() != null) {
                        runEventRepository.save(ScheduledRunEvent.builder().scheduleId(schedule.getId())
                                .plannedAt(schedule.getNextRunAt()).recordedAt(now)
                                .outcome(ScheduledRunOutcome.FAILED).reason(NOT_RUN_WHILE_SUSPENDED).build());
                    }
                    schedule.setNextRunAt(null);
                    expired++;
                    continue;
                }
                schedule.setNextRunAt(schedule.getRunAt());
            } else {
                schedule.setNextRunAt(ScheduleNextRunCalculator.computeAfterTrigger(schedule, now));
            }
            resumed++;
        }
        return new Result(resumed, expired);
    }

    /** Reprise pour tous les scenarios d'une application (restauration de l'application). */
    @Transactional
    public Result resumeForApplication(UUID applicationId) {
        List<Scenario> scenarios = entityManager.createQuery(
                        "SELECT s FROM Scenario s WHERE s.application.id = :id", Scenario.class)
                .setParameter("id", applicationId)
                .getResultList();
        int resumed = 0;
        int expired = 0;
        for (Scenario scenario : scenarios) {
            Result r = resume(scenario);
            resumed += r.resumed();
            expired += r.expired();
        }
        return new Result(resumed, expired);
    }
}
