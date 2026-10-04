package com.loadpilot.backend.repository;

import com.loadpilot.backend.entity.ExecutionStepResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExecutionStepResultRepository extends JpaRepository<ExecutionStepResult, UUID>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<ExecutionStepResult> {

    /**
     * Resultats d'une Execution, dans l'ordre chronologique reel (= ordre
     * d'execution des Steps), avec le Step associe deja initialise (JOIN
     * FETCH) pour construire les reponses sans requete supplementaire ni
     * risque de LazyInitializationException.
     */
    /**
     * Colonnes utiles a l'analyse dans le temps uniquement (pas d'entites) :
     * [captured_at, response_time_ms, success, http_status, error, step.id, step_name, step.name,
     * step_method, step.method, virtual_user] - rapide meme avec plusieurs dizaines de milliers de lignes.
     */
    @Query("SELECT r.timestamp, r.responseTime, r.success, r.httpStatus, r.error, s.id, r.stepName, s.name, r.stepMethod, s.method, "
            + "r.virtualUser FROM ExecutionStepResult r LEFT JOIN r.step s WHERE r.execution.id = :executionId")
    List<Object[]> findTimelineRows(@Param("executionId") UUID executionId);

    @Query("SELECT r FROM ExecutionStepResult r LEFT JOIN FETCH r.step WHERE r.execution.id = :executionId ORDER BY r.timestamp ASC")
    List<ExecutionStepResult> findByExecutionIdOrderByTimestampAsc(@Param("executionId") UUID executionId);

    // ---- Phase 11 (Dashboard) - "steps reellement executes" = nombre de
    // lignes ExecutionStepResult, PAS le nombre de Step configures sur les
    // scenarios (voir Phase 9 : politique stop-on-failure). Une seule
    // requete COUNT par statistique, jamais de N+1. ----

    long countBySuccess(boolean success);

    @Query("SELECT COUNT(r) FROM ExecutionStepResult r WHERE r.execution.scenario.application.id = :applicationId")
    long countByApplicationId(@Param("applicationId") UUID applicationId);

    @Query("SELECT COUNT(r) FROM ExecutionStepResult r WHERE r.execution.scenario.application.id = :applicationId AND r.success = :success")
    long countByApplicationIdAndSuccess(@Param("applicationId") UUID applicationId, @Param("success") boolean success);

    // ---- P1-C - Dashboard enrichi (plage temporelle) - "from"/"to"
    // toujours non-null au moment de l'appel (voir DashboardServiceImpl). ----

    long countByTimestampBetween(Instant from, Instant to);

    long countBySuccessAndTimestampBetween(boolean success, Instant from, Instant to);
}
