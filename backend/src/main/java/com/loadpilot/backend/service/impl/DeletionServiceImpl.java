package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.service.audit.AuditTargetType;
import com.loadpilot.backend.service.audit.AuditEvent;
import com.loadpilot.backend.dto.response.DeletionImpactResponse;
import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.entity.Scenario;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.exception.ConflictException;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.repository.ApplicationRepository;
import com.loadpilot.backend.repository.ScenarioRepository;
import com.loadpilot.backend.service.AuditLogService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Suppression coherente des Applications et Scenarios :
 *   - impact calcule AVANT confirmation (scenarios, etapes, executions...) ;
 *   - archivage logique (par defaut) : l'element disparait des listes,
 *     l'historique reste consultable, restauration possible ;
 *   - suppression definitive : cascade complete dans UNE transaction
 *     (metriques, resultats, executions, planifications, etapes, scenarios,
 *     application) ;
 *   - toujours refuse si une execution est en cours (QUEUED/RUNNING).
 * Chaque operation est tracee dans l'audit (succes et echec).
 *
 * Les suppressions en masse passent par des requetes SQL natives
 * (portables PostgreSQL/H2) : une seule instruction par table, au lieu de
 * charger chaque entite en memoire.
 */
@Service
@RequiredArgsConstructor
public class DeletionServiceImpl {

    public static final String RUNNING_MESSAGE = "Une exécution est en cours, arrêtez-la avant de supprimer.";
    public static final String FINALIZING_MESSAGE =
            "Une exécution vient de se terminer et enregistre encore ses résultats : réessayez dans quelques secondes.";
    private static final String RUNNING_ARCHIVE_MESSAGE = "Une exécution est en cours, arrêtez-la avant d'archiver.";

    private final ApplicationRepository applicationRepository;
    private final ScenarioRepository scenarioRepository;
    private final AuditLogService auditLogService;
    private final com.loadpilot.backend.service.execution.RunningExecutionRegistry registry;
    private final ScheduleResumer scheduleResumer;

    /**
     * Refus clair (409) si une execution concernee est terminee en base mais enregistre encore ses
     * resultats / metriques (tache asynchrone pas finie) : la supprimer maintenant provoquerait une
     * violation de cle etrangere (metriques inserees pendant la suppression).
     */
    private void checkNotFinalizing(String executionCondition, UUID id) {
        java.util.Set<UUID> active = registry.activeExecutionIds();
        if (active.isEmpty()) return;
        long finalizing = entityManager.createQuery("SELECT COUNT(e) FROM Execution e WHERE " + executionCondition
                        + " AND e.id IN :active", Long.class)
                .setParameter("id", id).setParameter("active", active).getSingleResult();
        if (finalizing > 0) throw new ConflictException(FINALIZING_MESSAGE);
    }

    @PersistenceContext
    private EntityManager entityManager;

    // ------------------------------------------------------------------ impact

    @Transactional(readOnly = true)
    public DeletionImpactResponse applicationsImpact(List<UUID> applicationIds) {
        List<UUID> ids = applicationIds.stream().distinct().toList();
        long existing = count("SELECT COUNT(a) FROM Application a WHERE a.id IN :ids", ids);
        if (existing == 0) {
            throw new ResourceNotFoundException("Application introuvable.");
        }
        long running = count("SELECT COUNT(e) FROM Execution e WHERE e.scenario.application.id IN :ids "
                + "AND e.status IN (com.loadpilot.backend.enums.ExecutionStatus.QUEUED, com.loadpilot.backend.enums.ExecutionStatus.RUNNING, com.loadpilot.backend.enums.ExecutionStatus.PAUSED)", ids);
        return new DeletionImpactResponse(
                existing,
                count("SELECT COUNT(s) FROM Scenario s WHERE s.application.id IN :ids", ids),
                count("SELECT COUNT(st) FROM Step st WHERE st.scenario.application.id IN :ids", ids),
                count("SELECT COUNT(e) FROM Execution e WHERE e.scenario.application.id IN :ids", ids),
                count("SELECT COUNT(se) FROM ScheduledExecution se WHERE se.scenario.application.id IN :ids", ids),
                running,
                running > 0);
    }

    @Transactional(readOnly = true)
    public DeletionImpactResponse scenarioImpact(UUID scenarioId) {
        findScenario(scenarioId);
        List<UUID> ids = List.of(scenarioId);
        long running = runningForScenario(scenarioId);
        return new DeletionImpactResponse(
                0,
                1,
                count("SELECT COUNT(st) FROM Step st WHERE st.scenario.id IN :ids", ids),
                count("SELECT COUNT(e) FROM Execution e WHERE e.scenario.id IN :ids", ids),
                count("SELECT COUNT(se) FROM ScheduledExecution se WHERE se.scenario.id IN :ids", ids),
                running,
                running > 0);
    }

    /** Impact cumule d'une action groupee sur plusieurs scenarios. */
    @Transactional(readOnly = true)
    public DeletionImpactResponse scenariosImpact(List<UUID> scenarioIds) {
        List<UUID> ids = scenarioIds.stream().distinct().toList();
        long existing = count("SELECT COUNT(s) FROM Scenario s WHERE s.id IN :ids", ids);
        if (existing == 0) {
            throw new ResourceNotFoundException("Scenario introuvable.");
        }
        long running = count("SELECT COUNT(e) FROM Execution e WHERE e.scenario.id IN :ids "
                + "AND e.status IN (com.loadpilot.backend.enums.ExecutionStatus.QUEUED, com.loadpilot.backend.enums.ExecutionStatus.RUNNING, com.loadpilot.backend.enums.ExecutionStatus.PAUSED)", ids);
        return new DeletionImpactResponse(
                0,
                existing,
                count("SELECT COUNT(st) FROM Step st WHERE st.scenario.id IN :ids", ids),
                count("SELECT COUNT(e) FROM Execution e WHERE e.scenario.id IN :ids", ids),
                count("SELECT COUNT(se) FROM ScheduledExecution se WHERE se.scenario.id IN :ids", ids),
                running,
                running > 0);
    }

    /** Nombre d'executions QUEUED/RUNNING d'un scenario (utilise aussi par la
     * suppression d'etape). */
    @Transactional(readOnly = true)
    public long runningForScenario(UUID scenarioId) {
        return count("SELECT COUNT(e) FROM Execution e WHERE e.scenario.id IN :ids "
                + "AND e.status IN (com.loadpilot.backend.enums.ExecutionStatus.QUEUED, com.loadpilot.backend.enums.ExecutionStatus.RUNNING, com.loadpilot.backend.enums.ExecutionStatus.PAUSED)",
                List.of(scenarioId));
    }

    // ------------------------------------------------------------------ application

    @Transactional
    public Application archiveApplication(UUID id) {
        Application application = findApplication(id);
        try {
            if (application.isArchived()) return application;
            if (applicationsImpact(List.of(id)).hasRunningExecution()) throw new ConflictException(RUNNING_ARCHIVE_MESSAGE);
            application.setArchived(true);
            application.setArchivedAt(Instant.now());
            int disabled = disableSchedules("s.application_id = :id", id);
            auditLogService.record(AuditEvent.of(AuditAction.ARCHIVE, AuditModule.APPLICATION)
                    .target(AuditTargetType.APPLICATION, id, application.getName())
                    .description("Application archivée" + (disabled > 0 ? " — " + disabled + " planification(s) suspendue(s)" : "")));
            return applicationRepository.saveAndFlush(application);
        } catch (RuntimeException e) {
            auditLogService.record(AuditEvent.of(AuditAction.ARCHIVE, AuditModule.APPLICATION)
                    .target(AuditTargetType.APPLICATION, id, application.getName())
                    .description("Archivage refusé").failed(e));
            throw e;
        }
    }

    @Transactional
    public Application restoreApplication(UUID id) {
        Application application = findApplication(id);
        if (!application.isArchived()) return application;
        application.setArchived(false);
        application.setArchivedAt(null);
        Application saved = applicationRepository.saveAndFlush(application);
        // Les planifications suspendues par l'archivage reprennent pour les scenarios actifs et non archives.
        ScheduleResumer.Result resumed = scheduleResumer.resumeForApplication(id);
        auditLogService.record(AuditEvent.of(AuditAction.RESTORE, AuditModule.APPLICATION)
                .target(AuditTargetType.APPLICATION, id, application.getName())
                .description("Application restaurée" + (resumed.resumed() > 0 ? " — " + resumed.resumed() + " planification(s) reprise(s)" : "")));
        return saved;
    }

    @Transactional
    public void deleteApplicationPermanently(UUID id) {
        Application application = findApplication(id);
        try {
            DeletionImpactResponse impact = applicationsImpact(List.of(id));
            if (impact.hasRunningExecution()) throw new ConflictException(RUNNING_MESSAGE);
            checkNotFinalizing("e.scenario.application.id = :id", id);
            entityManager.flush();
            String scenarios = "SELECT s.id FROM scenario s WHERE s.application_id = :id";
            String executions = "SELECT e.id FROM execution e WHERE e.scenario_id IN (" + scenarios + ")";
            String steps = "SELECT st.id FROM step st WHERE st.scenario_id IN (" + scenarios + ")";
            nativeDelete("DELETE FROM metric WHERE application_id = :id OR scenario_id IN (" + scenarios
                    + ") OR execution_id IN (" + executions + ") OR step_id IN (" + steps + ")", id);
            nativeDelete("DELETE FROM execution_step_result WHERE execution_id IN (" + executions + ")", id);
            nativeDelete("DELETE FROM execution WHERE scenario_id IN (" + scenarios + ")", id);
            nativeDelete("DELETE FROM scheduled_execution WHERE scenario_id IN (" + scenarios + ")", id);
            nativeDelete("DELETE FROM step WHERE scenario_id IN (" + scenarios + ")", id);
            nativeDelete("DELETE FROM scenario WHERE application_id = :id", id);
            nativeDelete("DELETE FROM application WHERE id = :id", id);
            entityManager.clear();
            auditLogService.record(AuditEvent.of(AuditAction.DELETE, AuditModule.APPLICATION)
                    .target(AuditTargetType.APPLICATION, id, application.getName())
                    .description("Application supprimée définitivement avec " + impact.scenarios() + " scénario(s), "
                            + impact.steps() + " étape(s) et " + impact.executions() + " exécution(s)"));
        } catch (RuntimeException e) {
            auditLogService.record(AuditEvent.of(AuditAction.DELETE, AuditModule.APPLICATION)
                    .target(AuditTargetType.APPLICATION, id, application.getName())
                    .description("Suppression définitive refusée").failed(e));
            throw e;
        }
    }

    // ------------------------------------------------------------------ scenario

    @Transactional
    public Scenario archiveScenario(UUID id) {
        Scenario scenario = findScenario(id);
        try {
            if (scenario.isArchived()) return scenario;
            if (runningForScenario(id) > 0) throw new ConflictException(RUNNING_ARCHIVE_MESSAGE);
            scenario.setArchived(true);
            scenario.setArchivedAt(Instant.now());
            int disabled = disableSchedules("s.id = :id", id);
            auditLogService.record(AuditEvent.of(AuditAction.ARCHIVE, AuditModule.SCENARIO)
                    .target(AuditTargetType.SCENARIO, id, scenario.getName())
                    .description("Scénario archivé" + (disabled > 0 ? " — " + disabled + " planification(s) suspendue(s)" : "")));
            return scenarioRepository.saveAndFlush(scenario);
        } catch (RuntimeException e) {
            auditLogService.record(AuditEvent.of(AuditAction.ARCHIVE, AuditModule.SCENARIO)
                    .target(AuditTargetType.SCENARIO, id, scenario.getName())
                    .description("Archivage refusé").failed(e));
            throw e;
        }
    }

    @Transactional
    public Scenario restoreScenario(UUID id) {
        Scenario scenario = findScenario(id);
        if (scenario.getApplication().isArchived()) {
            throw new ConflictException("L'application « " + scenario.getApplication().getName()
                    + " » est archivée : restaurez-la d'abord.");
        }
        if (!scenario.isArchived()) return scenario;
        scenario.setArchived(false);
        scenario.setArchivedAt(null);
        Scenario saved = scenarioRepository.saveAndFlush(scenario);
        // Planifications suspendues par l'archivage : reprennent si le scenario est actif.
        ScheduleResumer.Result resumed = scheduleResumer.resume(saved);
        auditLogService.record(AuditEvent.of(AuditAction.RESTORE, AuditModule.SCENARIO)
                .target(AuditTargetType.SCENARIO, id, scenario.getName())
                .description("Scénario restauré" + (resumed.resumed() > 0 ? " — " + resumed.resumed() + " planification(s) reprise(s)" : "")));
        return saved;
    }

    @Transactional
    public void deleteScenarioPermanently(UUID id) {
        Scenario scenario = findScenario(id);
        try {
            DeletionImpactResponse impact = scenarioImpact(id);
            if (impact.hasRunningExecution()) throw new ConflictException(RUNNING_MESSAGE);
            checkNotFinalizing("e.scenario.id = :id", id);
            entityManager.flush();
            String executions = "SELECT e.id FROM execution e WHERE e.scenario_id = :id";
            String steps = "SELECT st.id FROM step st WHERE st.scenario_id = :id";
            nativeDelete("DELETE FROM metric WHERE scenario_id = :id OR execution_id IN (" + executions
                    + ") OR step_id IN (" + steps + ")", id);
            nativeDelete("DELETE FROM execution_step_result WHERE execution_id IN (" + executions + ")", id);
            nativeDelete("DELETE FROM execution WHERE scenario_id = :id", id);
            nativeDelete("DELETE FROM scheduled_execution WHERE scenario_id = :id", id);
            nativeDelete("DELETE FROM step WHERE scenario_id = :id", id);
            nativeDelete("DELETE FROM scenario WHERE id = :id", id);
            entityManager.clear();
            auditLogService.record(AuditEvent.of(AuditAction.DELETE, AuditModule.SCENARIO)
                    .target(AuditTargetType.SCENARIO, id, scenario.getName())
                    .description("Scénario supprimé définitivement avec " + impact.steps() + " étape(s) et "
                            + impact.executions() + " exécution(s)"));
        } catch (RuntimeException e) {
            auditLogService.record(AuditEvent.of(AuditAction.DELETE, AuditModule.SCENARIO)
                    .target(AuditTargetType.SCENARIO, id, scenario.getName())
                    .description("Suppression définitive refusée").failed(e));
            throw e;
        }
    }

    // ------------------------------------------------------------------ helpers

    public Application findApplication(UUID id) {
        return applicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Application introuvable : " + id));
    }

    private Scenario findScenario(UUID id) {
        return scenarioRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Scenario introuvable : " + id));
    }

    private long count(String jpql, List<UUID> ids) {
        return entityManager.createQuery(jpql, Long.class).setParameter("ids", ids).getSingleResult();
    }

    private void nativeDelete(String sql, UUID id) {
        entityManager.createNativeQuery(sql).setParameter("id", id).executeUpdate();
    }

    /** Desactive les planifications des scenarios concernes (un element
     * archive ne doit plus etre declenche automatiquement). */
    private int disableSchedules(String scenarioCondition, UUID id) {
        return entityManager.createNativeQuery("UPDATE scheduled_execution SET enabled = FALSE, paused_by_scenario = TRUE WHERE enabled = TRUE "
                        + "AND scenario_id IN (SELECT s.id FROM scenario s WHERE " + scenarioCondition + ")")
                .setParameter("id", id)
                .executeUpdate();
    }
}
