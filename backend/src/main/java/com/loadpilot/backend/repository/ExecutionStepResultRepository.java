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

    /** Requetes les plus rapides (ASC) : temps de reponse croissant, puis la plus ancienne d'abord (index execution_id, response_time_ms). */
    @Query("SELECT r FROM ExecutionStepResult r LEFT JOIN FETCH r.step WHERE r.execution.id = :executionId AND r.responseTime IS NOT NULL "
            + "ORDER BY r.responseTime ASC, r.timestamp ASC")
    List<ExecutionStepResult> findFastest(@Param("executionId") UUID executionId, org.springframework.data.domain.Pageable page);

    /** Requetes les plus lentes : temps de reponse decroissant, puis la plus ancienne d'abord. */
    @Query("SELECT r FROM ExecutionStepResult r LEFT JOIN FETCH r.step WHERE r.execution.id = :executionId AND r.responseTime IS NOT NULL "
            + "ORDER BY r.responseTime DESC, r.timestamp ASC")
    List<ExecutionStepResult> findSlowest(@Param("executionId") UUID executionId, org.springframework.data.domain.Pageable page);

    @Query("SELECT COUNT(r) FROM ExecutionStepResult r WHERE r.execution.id = :executionId AND r.responseTime IS NOT NULL")
    long countMeasured(@Param("executionId") UUID executionId);

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

    /** Resultats enregistres d'une execution (garde-fou avant un chargement complet, voir ResultLoadGuard). */
    @Query("SELECT COUNT(r) FROM ExecutionStepResult r WHERE r.execution.id = :executionId")
    long countAllByExecutionId(@Param("executionId") UUID executionId);

    // ---- Moyennes de performance (Dashboard, /api/metrics), calculees directement sur les resultats -
    // remplacent l'ancienne table metric (une copie de chaque resultat). Une ligne par execution TERMINEE
    // (statuts actifs exclus, comme l'etaient les metric, generees apres la fin) : [execution.id,
    // requetes retenues, somme et nombre des temps de reponse]. Regroupement sur la seule cle etrangere,
    // sans jointure (PostgreSQL peut lire l'index (execution_id, response_time_ms) seul) ; les colonnes
    // des executions sont lues ensuite (executionTotals) - voir PerformanceAggregates pour les formules. ----

    // Executions en cours exclues PAR LEUR IDENTIFIANT (activeIds, quelques-unes au plus, jamais vide : voir
    // PerformanceAggregates) plutot que par une sous-requete, que certains moteurs evaluent ligne a ligne.
    String EXECUTION_GROUP_SELECT = "SELECT r.execution.id, COUNT(r), SUM(r.responseTime), COUNT(r.responseTime) "
            + "FROM ExecutionStepResult r WHERE r.execution.id NOT IN :activeIds ";
    String EXECUTION_GROUP_BY = " GROUP BY r.execution.id";

    /**
     * Sans plage de dates : toutes les requetes d'une execution comptent. [somme, nombre] des temps de
     * reponse en un seul parcours, sans regroupement ; les poids viennent de finishedExecutionTotals.
     */
    @Query("SELECT SUM(r.responseTime), COUNT(r.responseTime) FROM ExecutionStepResult r WHERE r.execution.id NOT IN :activeIds")
    List<Object[]> responseTimeTotals(@Param("activeIds") java.util.Collection<UUID> activeIds);

    @Query("SELECT SUM(r.responseTime), COUNT(r.responseTime) FROM ExecutionStepResult r JOIN r.execution e "
            + "WHERE e.scenario.application.id = :applicationId AND e.status NOT IN :active")
    List<Object[]> responseTimeTotalsForApplication(
            @Param("active") java.util.Collection<com.loadpilot.backend.enums.ExecutionStatus> active,
            @Param("applicationId") UUID applicationId);

    /**
     * [execution.id, requetes reussies, en echec, duree, duree en pause] des executions terminees dont les
     * resultats sont encore en base (non purges) et non vides : leurs requetes = reussies + echecs (compteurs
     * relus en base a la finalisation), chacune pesant comme une ancienne ligne metric.
     */
    String FINISHED_TOTALS = "SELECT e.id, e.successfulSteps, e.failedSteps, e.duration, e.pausedDurationMs FROM Execution e "
            + "WHERE e.status NOT IN :active AND e.resultsPurgedAt IS NULL AND (e.successfulSteps + e.failedSteps) > 0 "
            // Au moins un resultat en base (index execution_id) : une execution sans requete enregistree n'avait pas de metric.
            + "AND EXISTS (SELECT 1 FROM ExecutionStepResult x WHERE x.execution.id = e.id)";

    @Query(FINISHED_TOTALS)
    List<Object[]> finishedExecutionTotals(@Param("active") java.util.Collection<com.loadpilot.backend.enums.ExecutionStatus> active);

    @Query(FINISHED_TOTALS + " AND e.scenario.application.id = :applicationId")
    List<Object[]> finishedExecutionTotalsForApplication(
            @Param("active") java.util.Collection<com.loadpilot.backend.enums.ExecutionStatus> active,
            @Param("applicationId") UUID applicationId);

    @Query(EXECUTION_GROUP_SELECT + "AND r.timestamp BETWEEN :from AND :to" + EXECUTION_GROUP_BY)
    List<Object[]> performanceByExecutionBetween(@Param("activeIds") java.util.Collection<UUID> activeIds,
            @Param("from") Instant from, @Param("to") Instant to);

    /** Executions pas encore terminees (quelques-unes au plus : limite d'executions simultanees). */
    @Query("SELECT e.id FROM Execution e WHERE e.status IN :active")
    List<UUID> activeExecutionIds(@Param("active") java.util.Collection<com.loadpilot.backend.enums.ExecutionStatus> active);

    /** [execution.id, requetes reussies, en echec, duree, duree en pause] des executions donnees (une ligne chacune). */
    @Query("SELECT e.id, e.successfulSteps, e.failedSteps, e.duration, e.pausedDurationMs FROM Execution e WHERE e.id IN :ids")
    List<Object[]> executionTotals(@Param("ids") java.util.Collection<UUID> ids);

    /** Temps de reponse moyen par scenario (widget « Top scenarios »), une seule requete groupee. */
    @Query("SELECT e.scenario.id AS scenarioId, AVG(r.responseTime) AS averageResponseTime FROM ExecutionStepResult r JOIN r.execution e "
            + "WHERE e.status NOT IN :active AND e.scenario.id IN :scenarioIds AND r.timestamp BETWEEN :from AND :to "
            + "GROUP BY e.scenario.id")
    List<ScenarioAverageResponseTimeProjection> averageResponseTimeByScenarioIn(
            @Param("active") java.util.Collection<com.loadpilot.backend.enums.ExecutionStatus> active,
            @Param("scenarioIds") java.util.Collection<UUID> scenarioIds, @Param("from") Instant from, @Param("to") Instant to);

    /**
     * Lignes de /api/metrics : resultats des executions terminees, filtre combinable (chaque parametre
     * null est ignore), execution et scenario charges (JOIN FETCH) pour construire chaque ligne.
     */
    @Query("SELECT r FROM ExecutionStepResult r JOIN FETCH r.execution e JOIN FETCH e.scenario s LEFT JOIN FETCH r.step st "
            + "WHERE e.status NOT IN :active "
            + "AND (:applicationId IS NULL OR s.application.id = :applicationId) AND (:scenarioId IS NULL OR s.id = :scenarioId) "
            + "AND (:stepId IS NULL OR st.id = :stepId) AND (:executionId IS NULL OR e.id = :executionId) ORDER BY r.timestamp ASC")
    List<ExecutionStepResult> searchMetricRows(@Param("active") java.util.Collection<com.loadpilot.backend.enums.ExecutionStatus> active,
            @Param("applicationId") UUID applicationId, @Param("scenarioId") UUID scenarioId,
            @Param("stepId") UUID stepId, @Param("executionId") UUID executionId);

    /** Nombre de lignes que renverrait searchMetricRows (garde-fou avant le chargement, voir ResultLoadGuard). */
    @Query("SELECT COUNT(r) FROM ExecutionStepResult r JOIN r.execution e JOIN e.scenario s LEFT JOIN r.step st "
            + "WHERE e.status NOT IN :active "
            + "AND (:applicationId IS NULL OR s.application.id = :applicationId) AND (:scenarioId IS NULL OR s.id = :scenarioId) "
            + "AND (:stepId IS NULL OR st.id = :stepId) AND (:executionId IS NULL OR e.id = :executionId)")
    long countMetricRows(@Param("active") java.util.Collection<com.loadpilot.backend.enums.ExecutionStatus> active,
            @Param("applicationId") UUID applicationId, @Param("scenarioId") UUID scenarioId,
            @Param("stepId") UUID stepId, @Param("executionId") UUID executionId);

    /** Requetes reussies / en echec deja enregistrees (resultats ecrits pendant le test, voir ResultStream). */
    @Query("SELECT COUNT(r) FROM ExecutionStepResult r WHERE r.execution.id = :executionId AND r.success = :success")
    long countByExecutionIdAndSuccess(@Param("executionId") UUID executionId, @Param("success") boolean success);
}
