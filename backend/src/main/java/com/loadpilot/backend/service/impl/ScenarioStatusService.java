package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.entity.Scenario;
import com.loadpilot.backend.entity.ScheduledExecution;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.enums.ScenarioStatus;
import com.loadpilot.backend.enums.ScheduleType;
import com.loadpilot.backend.exception.BadRequestException;
import com.loadpilot.backend.exception.ConflictException;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.repository.ScenarioRepository;
import com.loadpilot.backend.service.AuditLogService;
import com.loadpilot.backend.service.execution.ScheduleNextRunCalculator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Activation / desactivation d'un scenario (statut INACTIVE fonctionnel).
 *
 * Desactiver : refuse pour un brouillon ou si une execution est en cours ; le scenario reste
 * visible et modifiable, n'est plus executable, et ses planifications actives sont suspendues
 * (enabled=false, pausedByScenario=true) - le poller les ignore de toute facon (filtre sur
 * le statut ACTIVE). Activer : le scenario redevient executable ; ses planifications
 * suspendues sont proposees a la reactivation (resumeSchedules), jamais reactivees d'office.
 * Chaque changement est trace dans l'audit (ENABLE / DISABLE, module SCENARIO).
 */
@Service
@RequiredArgsConstructor
public class ScenarioStatusService {

    public static final String DRAFT_MESSAGE =
            "Ce scénario est un brouillon : terminez-le dans l'assistant avant de l'activer ou de le désactiver.";
    public static final String RUNNING_MESSAGE =
            "Une exécution est en cours : attendez sa fin ou arrêtez-la avant de désactiver le scénario.";

    private final ScenarioRepository scenarioRepository;
    private final DeletionServiceImpl deletionService;
    private final AuditLogService auditLogService;

    @PersistenceContext
    private EntityManager entityManager;

    /** Resultat de reactivation des planifications suspendues. */
    public record ResumeResult(int resumed, int expired) {
    }

    @Transactional
    public Scenario deactivate(UUID id) {
        Scenario scenario = find(id);
        if (scenario.getStatus() == ScenarioStatus.DRAFT) throw new BadRequestException(DRAFT_MESSAGE);
        if (scenario.getStatus() == ScenarioStatus.INACTIVE) return scenario;
        try {
            if (deletionService.runningForScenario(id) > 0) throw new ConflictException(RUNNING_MESSAGE);
            scenario.setStatus(ScenarioStatus.INACTIVE);
            int paused = entityManager.createQuery("UPDATE ScheduledExecution se SET se.enabled = false, se.pausedByScenario = true "
                            + "WHERE se.scenario.id = :id AND se.enabled = true")
                    .setParameter("id", id)
                    .executeUpdate();
            Scenario saved = scenarioRepository.saveAndFlush(scenario);
            auditLogService.record(AuditAction.DISABLE, AuditModule.SCENARIO, AuditResult.SUCCESS,
                    "Scenario deactivated: " + scenario.getName() + (paused > 0 ? " (" + paused + " schedule(s) paused)" : ""));
            return saved;
        } catch (RuntimeException e) {
            auditLogService.record(AuditAction.DISABLE, AuditModule.SCENARIO, AuditResult.FAILURE,
                    "Scenario deactivation failed: " + scenario.getName() + ": " + e.getClass().getSimpleName());
            throw e;
        }
    }

    @Transactional
    public Scenario activate(UUID id) {
        Scenario scenario = find(id);
        if (scenario.getStatus() == ScenarioStatus.DRAFT) throw new BadRequestException(DRAFT_MESSAGE);
        if (scenario.getStatus() == ScenarioStatus.ACTIVE) return scenario;
        scenario.setStatus(ScenarioStatus.ACTIVE);
        Scenario saved = scenarioRepository.saveAndFlush(scenario);
        auditLogService.record(AuditAction.ENABLE, AuditModule.SCENARIO, AuditResult.SUCCESS,
                "Scenario activated: " + scenario.getName());
        return saved;
    }

    /**
     * Reactive les planifications suspendues par la desactivation. Une planification
     * ponctuelle dont la date est passee pendant l'inactivite n'est jamais declenchee en
     * rattrapage : elle reste desactivee (comptee dans "expired"). Une planification cron
     * repart de la prochaine occurrence a partir de maintenant.
     */
    @Transactional
    public ResumeResult resumeSchedules(UUID id) {
        Scenario scenario = find(id);
        if (scenario.getStatus() != ScenarioStatus.ACTIVE || scenario.isArchived() || scenario.getApplication().isArchived()) {
            throw new ConflictException("Activez (et restaurez si besoin) le scénario avant de réactiver ses planifications.");
        }
        List<ScheduledExecution> paused = entityManager.createQuery(
                        "SELECT se FROM ScheduledExecution se WHERE se.scenario.id = :id AND se.pausedByScenario = true",
                        ScheduledExecution.class)
                .setParameter("id", id)
                .getResultList();
        Instant now = Instant.now();
        int resumed = 0;
        int expired = 0;
        for (ScheduledExecution schedule : paused) {
            schedule.setPausedByScenario(false);
            if (schedule.getScheduleType() == ScheduleType.ONE_TIME && (schedule.getRunAt() == null || !schedule.getRunAt().isAfter(now))) {
                expired++;
                continue;
            }
            schedule.setNextRunAt(schedule.getScheduleType() == ScheduleType.ONE_TIME
                    ? schedule.getRunAt()
                    : ScheduleNextRunCalculator.computeInitial(schedule.getScheduleType(), schedule.getRunAt(),
                            schedule.getCronExpression(), schedule.getTimezone(), now));
            schedule.setEnabled(true);
            resumed++;
        }
        if (!paused.isEmpty()) {
            auditLogService.record(AuditAction.ENABLE, AuditModule.SCHEDULING, AuditResult.SUCCESS,
                    "Schedules resumed for scenario " + scenario.getName() + ": " + resumed + " resumed, " + expired + " expired");
        }
        return new ResumeResult(resumed, expired);
    }

    /** Nombre de planifications suspendues par scenario (une seule requete pour une page). */
    @Transactional(readOnly = true)
    public Map<UUID, Long> pausedSchedulesCount(Collection<UUID> scenarioIds) {
        Map<UUID, Long> counts = new HashMap<>();
        if (scenarioIds.isEmpty()) return counts;
        List<Object[]> rows = entityManager.createQuery(
                        "SELECT se.scenario.id, COUNT(se) FROM ScheduledExecution se "
                                + "WHERE se.pausedByScenario = true AND se.scenario.id IN :ids GROUP BY se.scenario.id", Object[].class)
                .setParameter("ids", scenarioIds)
                .getResultList();
        rows.forEach(r -> counts.put((UUID) r[0], (Long) r[1]));
        return counts;
    }

    public String nameOf(UUID id) {
        return find(id).getName();
    }

    private Scenario find(UUID id) {
        return scenarioRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Scenario introuvable : " + id));
    }
}
