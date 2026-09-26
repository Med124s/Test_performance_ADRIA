package com.loadpilot.backend.service.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.loadpilot.backend.entity.ExecutionStepResult;
import com.loadpilot.backend.entity.Step;
import com.loadpilot.backend.enums.HttpMethod;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * P1-A — teste PerformanceStatisticsService en isolation (aucun contexte
 * Spring/base de donnees necessaire : ExecutionStepResult/Step sont de
 * simples objets construits en memoire, jamais persistes ici) sur des
 * scenarios reels : 0/1/plusieurs resultats, tout succes, mixte, tout
 * echec - avec verification des VALEURS numeriques exactes (jamais
 * seulement "ca ne plante pas", voir prompt section 33/34).
 */
class PerformanceStatisticsServiceTest {

    private final PerformanceStatisticsService service = new PerformanceStatisticsService();

    private Step step(String name, String url) {
        return Step.builder().id(UUID.randomUUID()).name(name).method(HttpMethod.GET).url(url).order(1).build();
    }

    private ExecutionStepResult result(Step step, long responseTimeMs, boolean success, Integer httpStatus) {
        return ExecutionStepResult.builder()
                .step(step)
                .responseTime(responseTimeMs)
                .success(success)
                .httpStatus(httpStatus)
                .error(success ? null : "Reponse HTTP " + httpStatus)
                .timestamp(Instant.now())
                .build();
    }

    @Test
    void computeOverallStatistics_onEmptyResults_returnsZeroCountsAndNullMeasurements() {
        ExecutionStatistics stats = service.computeOverallStatistics(List.of(), 5000L);

        assertThat(stats.totalRequests()).isZero();
        assertThat(stats.successfulRequests()).isZero();
        assertThat(stats.failedRequests()).isZero();
        assertThat(stats.successRate()).isNull();
        assertThat(stats.errorRate()).isNull();
        assertThat(stats.minResponseTime()).isNull();
        assertThat(stats.maxResponseTime()).isNull();
        assertThat(stats.avgResponseTime()).isNull();
        assertThat(stats.p95()).isNull();
        assertThat(stats.stdDevResponseTime()).isNull();
        assertThat(stats.throughput()).isNull();
    }

    @Test
    void computeOverallStatistics_onSingleSuccessfulResult() {
        Step step = step("login", "/login");
        List<ExecutionStepResult> results = List.of(result(step, 42L, true, 200));

        ExecutionStatistics stats = service.computeOverallStatistics(results, 1000L);

        assertThat(stats.totalRequests()).isEqualTo(1);
        assertThat(stats.successfulRequests()).isEqualTo(1);
        assertThat(stats.failedRequests()).isZero();
        assertThat(stats.successRate()).isEqualByComparingTo("100.00");
        assertThat(stats.errorRate()).isEqualByComparingTo("0.00");
        assertThat(stats.minResponseTime()).isEqualTo(42L);
        assertThat(stats.maxResponseTime()).isEqualTo(42L);
        assertThat(stats.avgResponseTime()).isEqualTo(42L);
        assertThat(stats.p50()).isEqualTo(42L);
        assertThat(stats.p95()).isEqualTo(42L);
        assertThat(stats.p99()).isEqualTo(42L);
        // Ecart-type d'un singleton = 0, une vraie valeur (jamais null).
        assertThat(stats.stdDevResponseTime()).isEqualTo(0L);
        // throughput = 1 requete / 1s = 1.000
        assertThat(stats.throughput()).isEqualByComparingTo("1.000");
    }

    @Test
    void computeOverallStatistics_allSuccess_zeroErrors() {
        Step step = step("health", "/health");
        List<ExecutionStepResult> results = List.of(
                result(step, 10L, true, 200),
                result(step, 20L, true, 200),
                result(step, 30L, true, 200));

        ExecutionStatistics stats = service.computeOverallStatistics(results, 3000L);

        assertThat(stats.totalRequests()).isEqualTo(3);
        assertThat(stats.successfulRequests()).isEqualTo(3);
        assertThat(stats.failedRequests()).isZero();
        assertThat(stats.errorRate()).isEqualByComparingTo("0.00");
        assertThat(stats.successRate()).isEqualByComparingTo("100.00");
        assertThat(stats.avgResponseTime()).isEqualTo(20L);
        // mean=20, variance=((10)^2+0+(10)^2)/3=66.67, sqrt=8.165 -> 8.
        assertThat(stats.stdDevResponseTime()).isEqualTo(8L);
    }

    @Test
    void computeOverallStatistics_allFailures_hundredPercentErrorRate() {
        Step step = step("payment", "/pay");
        List<ExecutionStepResult> results = List.of(
                result(step, 100L, false, 500),
                result(step, 200L, false, 503));

        ExecutionStatistics stats = service.computeOverallStatistics(results, 2000L);

        assertThat(stats.successfulRequests()).isZero();
        assertThat(stats.failedRequests()).isEqualTo(2);
        assertThat(stats.errorRate()).isEqualByComparingTo("100.00");
        assertThat(stats.successRate()).isEqualByComparingTo("0.00");
    }

    @Test
    void computeOverallStatistics_mixedSuccessAndFailure() {
        Step step = step("checkout", "/checkout");
        List<ExecutionStepResult> results = List.of(
                result(step, 10L, true, 200),
                result(step, 20L, true, 200),
                result(step, 30L, true, 200),
                result(step, 999L, false, 500));

        ExecutionStatistics stats = service.computeOverallStatistics(results, 4000L);

        assertThat(stats.totalRequests()).isEqualTo(4);
        assertThat(stats.successfulRequests()).isEqualTo(3);
        assertThat(stats.failedRequests()).isEqualTo(1);
        // errorRate = 1/4 * 100 = 25.00 (jamais une division par zero, jamais arrondi a 0 ou 100).
        assertThat(stats.errorRate()).isEqualByComparingTo("25.00");
        assertThat(stats.successRate()).isEqualByComparingTo("75.00");
        assertThat(stats.maxResponseTime()).isEqualTo(999L);
    }

    @Test
    void throughput_isNullWhenDurationIsNullOrZero_neverDividesByZero() {
        Step step = step("s", "/s");
        List<ExecutionStepResult> results = List.of(result(step, 10L, true, 200));

        assertThat(service.computeOverallStatistics(results, null).throughput()).isNull();
        assertThat(service.computeOverallStatistics(results, 0L).throughput()).isNull();
    }

    @Test
    void computeStepBreakdown_groupsByRealStepIdentity_neverByNameAlone() {
        Step stepA = step("same-name", "/a");
        Step stepB = step("same-name", "/b");
        List<ExecutionStepResult> results = List.of(
                result(stepA, 10L, true, 200),
                result(stepA, 20L, true, 200),
                result(stepB, 999L, false, 500));

        List<StepReportEntry> breakdown = service.computeStepBreakdown(results);

        assertThat(breakdown).hasSize(2);
        StepReportEntry entryA = breakdown.stream().filter(e -> e.url().equals("/a")).findFirst().orElseThrow();
        StepReportEntry entryB = breakdown.stream().filter(e -> e.url().equals("/b")).findFirst().orElseThrow();
        assertThat(entryA.total()).isEqualTo(2);
        assertThat(entryA.success()).isEqualTo(2);
        assertThat(entryA.failed()).isZero();
        // P1-C : mean=15, variance=((5)^2+(5)^2)/2=25, sqrt=5.
        assertThat(entryA.stdDevResponseTime()).isEqualTo(5L);
        assertThat(entryB.total()).isEqualTo(1);
        assertThat(entryB.failed()).isEqualTo(1);
        assertThat(entryB.errorRate()).isEqualByComparingTo("100.00");
    }

    @Test
    void extractErrors_returnsOnlyFailedResults_withRealFields() {
        Step step = step("login", "/login");
        ExecutionStepResult ok = result(step, 10L, true, 200);
        ExecutionStepResult failed = result(step, 999L, false, 500);

        List<ReportErrorEntry> errors = service.extractErrors(List.of(ok, failed));

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).httpStatus()).isEqualTo(500);
        assertThat(errors.get(0).stepName()).isEqualTo("login");
        assertThat(errors.get(0).error()).isEqualTo("Reponse HTTP 500");
    }
}
