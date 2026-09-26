package com.loadpilot.backend.repository;

import com.loadpilot.backend.entity.Metric;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MetricRepository extends JpaRepository<Metric, UUID> {

    List<Metric> findByApplicationId(UUID applicationId, Sort sort);

    List<Metric> findByScenarioId(UUID scenarioId, Sort sort);

    List<Metric> findByStepId(UUID stepId, Sort sort);

    List<Metric> findByExecutionId(UUID executionId, Sort sort);

    /**
     * Filtre combinable (logique ET) utilise par MetricController - chaque
     * parametre null est ignore. Prefere a un enchainement de
     * findByXxxAndYyy explicites : le nombre de combinaisons possibles
     * (4 filtres optionnels) rendrait ces derniers ingerables.
     */
    @Query("SELECT m FROM Metric m WHERE "
            + "(:applicationId IS NULL OR m.application.id = :applicationId) AND "
            + "(:scenarioId IS NULL OR m.scenario.id = :scenarioId) AND "
            + "(:stepId IS NULL OR m.step.id = :stepId) AND "
            + "(:executionId IS NULL OR m.execution.id = :executionId)")
    List<Metric> search(@Param("applicationId") UUID applicationId,
                         @Param("scenarioId") UUID scenarioId,
                         @Param("stepId") UUID stepId,
                         @Param("executionId") UUID executionId,
                         Sort sort);

    // ---- Phase 11 (Dashboard) - moyennes reelles sur toutes les Metric
    // existantes (ou filtrees par Application). AVG() sur un ensemble vide
    // renvoie NULL en JPQL - jamais 0 invente ; declare en Double (et non
    // BigDecimal) pour rester portable entre H2/PostgreSQL/Hibernate, la
    // conversion en BigDecimal arrondi se fait cote service. ----

    @Query("SELECT AVG(m.responseTime) FROM Metric m")
    Double averageResponseTime();

    @Query("SELECT AVG(m.throughput) FROM Metric m")
    Double averageThroughput();

    @Query("SELECT AVG(m.errorRate) FROM Metric m")
    Double averageErrorRate();

    @Query("SELECT AVG(m.responseTime) FROM Metric m WHERE m.application.id = :applicationId")
    Double averageResponseTimeByApplication(@Param("applicationId") UUID applicationId);

    @Query("SELECT AVG(m.throughput) FROM Metric m WHERE m.application.id = :applicationId")
    Double averageThroughputByApplication(@Param("applicationId") UUID applicationId);

    @Query("SELECT AVG(m.errorRate) FROM Metric m WHERE m.application.id = :applicationId")
    Double averageErrorRateByApplication(@Param("applicationId") UUID applicationId);

    // ---- P1-C - Dashboard enrichi (plage temporelle) - "from"/"to"
    // toujours non-null au moment de l'appel (voir DashboardServiceImpl). ----

    @Query("SELECT AVG(m.responseTime) FROM Metric m WHERE m.timestamp BETWEEN :from AND :to")
    Double averageResponseTimeBetween(@Param("from") Instant from, @Param("to") Instant to);

    @Query("SELECT AVG(m.throughput) FROM Metric m WHERE m.timestamp BETWEEN :from AND :to")
    Double averageThroughputBetween(@Param("from") Instant from, @Param("to") Instant to);

    @Query("SELECT AVG(m.errorRate) FROM Metric m WHERE m.timestamp BETWEEN :from AND :to")
    Double averageErrorRateBetween(@Param("from") Instant from, @Param("to") Instant to);

    /** Moyenne de temps de reponse groupee par scenario, restreinte a un
     * sous-ensemble de scenarios (les "top scenarios" deja identifies via
     * ExecutionRepository#findTopScenariosByExecutionCount) et a une plage
     * temporelle - jamais une requete par scenario (pas de N+1). */
    @Query("SELECT m.scenario.id AS scenarioId, AVG(m.responseTime) AS averageResponseTime FROM Metric m "
            + "WHERE m.scenario.id IN :scenarioIds AND m.timestamp BETWEEN :from AND :to "
            + "GROUP BY m.scenario.id")
    List<ScenarioAverageResponseTimeProjection> averageResponseTimeByScenarioIn(
            @Param("scenarioIds") Collection<UUID> scenarioIds, @Param("from") Instant from, @Param("to") Instant to);
}
