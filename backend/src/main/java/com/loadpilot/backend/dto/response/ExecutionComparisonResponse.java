package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.ExecutionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Comparaison de 2 a 4 executions terminees, une colonne par execution (ordre chronologique). */
public record ExecutionComparisonResponse(
        boolean sameScenario,
        String warning,
        List<Column> executions,
        List<StepRow> steps,
        /**
         * Tests de stress du meme scenario uniquement : point de rupture consolide (meme calcul qu'une serie d'essais) ;
         * null sinon.
         */
        com.loadpilot.backend.service.execution.StressSeriesSummary stressSummary
) {
    public ExecutionComparisonResponse(boolean sameScenario, String warning, List<Column> executions, List<StepRow> steps) {
        this(sameScenario, warning, executions, steps, null);
    }

    public record Column(String executionId, String scenarioId, String scenarioName, String applicationName, Instant startedAt,
            ExecutionStatus status, Integer virtualUsers, Integer rampUpSeconds, Integer durationSeconds, Integer iterations,
            /** Duree de travail reelle (ms), pauses exclues. */
            Long activeDurationMs, int requests, BigDecimal throughput, Long p95Ms, Long p99Ms, BigDecimal errorRate,
            Double cpuMaxPercent, Double memoryMaxMb,
            /** Mode de charge ; test de stress : debit offert au point de rupture (null sans rupture). */
            com.loadpilot.backend.enums.LoadMode loadMode, Double breakpointOfferedRps) {
    }

    public record StepRow(String name, String method, List<Long> p95Ms) {
    }
}
