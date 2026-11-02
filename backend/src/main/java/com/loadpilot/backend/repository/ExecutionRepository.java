package com.loadpilot.backend.repository;

import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.enums.ExecutionStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExecutionRepository extends JpaRepository<Execution, UUID>, JpaSpecificationExecutor<Execution> {

    List<Execution> findByScenarioId(UUID scenarioId, Sort sort);

    /** Execution avec son scenario et son application deja charges. */
    @Query("SELECT e FROM Execution e JOIN FETCH e.scenario s JOIN FETCH s.application LEFT JOIN FETCH e.triggeredBy WHERE e.id = :id")
    Optional<Execution> findByIdWithScenarioAndApplication(@Param("id") UUID id);

    /** Execution verrouillee (SELECT ... FOR UPDATE) pour modifier son statut. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Execution e WHERE e.id = :id")
    Optional<Execution> findByIdForUpdate(@Param("id") UUID id);

    /** Comme findByIdForUpdate, avec le scenario et l'application deja charges. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Execution e JOIN FETCH e.scenario s JOIN FETCH s.application WHERE e.id = :id")
    Optional<Execution> findByIdWithScenarioAndApplicationForUpdate(@Param("id") UUID id);

    /** Executions encore en attente ou en cours au demarrage du backend (orphelines). */
    List<Execution> findByStatusIn(Collection<ExecutionStatus> statuses);

    /** Executions en pause depuis avant "threshold" (voir PausedExecutionWatchdog). */
    List<Execution> findByStatusAndPausedAtBefore(ExecutionStatus status, Instant threshold);


    // ---- Tableau de bord ----

    long countByStatus(ExecutionStatus status);

    @Query("SELECT COUNT(e) FROM Execution e WHERE e.scenario.application.id = :applicationId")
    long countByApplicationId(@Param("applicationId") UUID applicationId);

    @Query("SELECT COUNT(e) FROM Execution e WHERE e.scenario.application.id = :applicationId AND e.status = :status")
    long countByApplicationIdAndStatus(@Param("applicationId") UUID applicationId, @Param("status") ExecutionStatus status);

    /** Nombre d'executions lancees par utilisateur (sujet Keycloak -> nombre). */
    @Query("SELECT u.keycloakSubject AS subject, COUNT(e) AS total FROM Execution e JOIN e.triggeredBy u GROUP BY u.keycloakSubject")
    List<ExecutionCountBySubject> countExecutionsBySubject();

    interface ExecutionCountBySubject {
        String getSubject();
        long getTotal();
    }

    // ---- Tableau de bord sur une periode ----

    long countByStartedAtBetween(Instant from, Instant to);

    long countByStatusAndStartedAtBetween(ExecutionStatus status, Instant from, Instant to);

    /** Scenarios ayant le plus d'executions sur la periode. */
    @Query("SELECT sc.id AS scenarioId, sc.name AS scenarioName, app.name AS applicationName, "
            + "COUNT(e) AS executionCount, "
            + "SUM(CASE WHEN e.status = :successStatus THEN 1L ELSE 0L END) AS successCount "
            + "FROM Execution e JOIN e.scenario sc JOIN sc.application app "
            + "WHERE e.startedAt BETWEEN :from AND :to "
            + "GROUP BY sc.id, sc.name, app.name "
            + "ORDER BY COUNT(e) DESC")
    List<ScenarioExecutionCountProjection> findTopScenariosByExecutionCount(
            @Param("from") Instant from, @Param("to") Instant to,
            @Param("successStatus") ExecutionStatus successStatus, Pageable pageable);

    /** Executions encore actives (en attente, en cours, en pause) d'un scenario. */
    @Query("SELECT COUNT(e) FROM Execution e WHERE e.scenario.id = :scenarioId AND e.status IN "
            + "(com.loadpilot.backend.enums.ExecutionStatus.QUEUED, com.loadpilot.backend.enums.ExecutionStatus.RUNNING, "
            + "com.loadpilot.backend.enums.ExecutionStatus.PAUSED)")
    long countActiveByScenarioId(@Param("scenarioId") UUID scenarioId);

    /** Executions lancees par une planification, de la plus recente a la plus ancienne. */
    @Query("SELECT e FROM Execution e WHERE e.scheduleId = :scheduleId ORDER BY e.startedAt DESC")
    List<Execution> findByScheduleIdRecentFirst(@Param("scheduleId") UUID scheduleId, Pageable pageable);

    /** Derniere execution terminee (reussie ou echouee) d'une planification lancee avant "before". */
    @Query("SELECT e FROM Execution e WHERE e.scheduleId = :scheduleId AND e.startedAt < :before AND e.status IN "
            + "(com.loadpilot.backend.enums.ExecutionStatus.SUCCESS, com.loadpilot.backend.enums.ExecutionStatus.THRESHOLDS_EXCEEDED, "
            + "com.loadpilot.backend.enums.ExecutionStatus.FAILED) "
            + "ORDER BY e.startedAt DESC")
    List<Execution> findPreviousFinishedBySchedule(@Param("scheduleId") UUID scheduleId, @Param("before") Instant before, Pageable pageable);

    /** Derniere execution terminee (reussie, seuils depasses ou echouee) du scenario lancee avant "before". */
    @Query("SELECT e FROM Execution e WHERE e.scenario.id = :scenarioId AND e.startedAt < :before AND e.status IN "
            + "(com.loadpilot.backend.enums.ExecutionStatus.SUCCESS, com.loadpilot.backend.enums.ExecutionStatus.THRESHOLDS_EXCEEDED, "
            + "com.loadpilot.backend.enums.ExecutionStatus.FAILED) ORDER BY e.startedAt DESC")
    List<Execution> findPreviousFinishedByScenario(@Param("scenarioId") UUID scenarioId, @Param("before") Instant before, Pageable pageable);

    // ---- Conservation des resultats detailles (voir ResultsRetentionJob, ReportSnapshotService) ----

    String TERMINATED = "e.status NOT IN (com.loadpilot.backend.enums.ExecutionStatus.QUEUED, "
            + "com.loadpilot.backend.enums.ExecutionStatus.RUNNING, com.loadpilot.backend.enums.ExecutionStatus.PAUSED)";

    /** Executions terminees, detail encore present, sans rapport fige. */
    @Query("SELECT e.id FROM Execution e WHERE " + TERMINATED + " AND e.resultsPurgedAt IS NULL "
            + "AND NOT EXISTS (SELECT 1 FROM ExecutionReportSnapshot s WHERE s.executionId = e.id) ORDER BY e.startedAt")
    List<UUID> findFinishedIdsWithoutSnapshot();

    /** Executions terminees avant "cutoff", non conservees, dont les resultats sont encore presents (plus anciennes d'abord). */
    @Query("SELECT e.id FROM Execution e WHERE " + TERMINATED + " AND e.keepResults = false AND e.resultsPurgedAt IS NULL "
            + "AND COALESCE(e.finishedAt, e.startedAt) < :cutoff ORDER BY COALESCE(e.finishedAt, e.startedAt)")
    List<UUID> findResultsToPurge(@Param("cutoff") Instant cutoff, Pageable pageable);

    /** Idem pour le detail des requetes (en-tetes et corps). */
    @Query("SELECT e.id FROM Execution e WHERE " + TERMINATED + " AND e.keepResults = false AND e.resultsPurgedAt IS NULL "
            + "AND e.requestDetailsPurgedAt IS NULL AND COALESCE(e.finishedAt, e.startedAt) < :cutoff "
            + "ORDER BY COALESCE(e.finishedAt, e.startedAt)")
    List<UUID> findRequestDetailsToPurge(@Param("cutoff") Instant cutoff, Pageable pageable);
}