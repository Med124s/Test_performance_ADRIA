package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.ExecutionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Comparaison de 2 a 4 executions terminees (GET /api/executions/compare?ids=) : une colonne par
 * execution (ordre chronologique), valeurs calculees exactement comme le rapport de chacune.
 * steps : P95 de chaque etape (meme nom et meme methode) dans chaque execution, null si absente.
 * warning : non nul si les executions n'utilisent pas toutes le meme scenario.
 */
public record ExecutionComparisonResponse(
        boolean sameScenario,
        String warning,
        List<Column> executions,
        List<StepRow> steps
) {
    public record Column(String executionId, String scenarioId, String scenarioName, String applicationName, Instant startedAt,
            ExecutionStatus status, Integer virtualUsers, Integer rampUpSeconds, Integer durationSeconds, Integer iterations,
            /** Duree de travail reelle (ms), pauses exclues. */
            Long activeDurationMs, int requests, BigDecimal throughput, Long p95Ms, Long p99Ms, BigDecimal errorRate,
            Double cpuMaxPercent, Double memoryMaxMb) {
    }

    public record StepRow(String name, String method, List<Long> p95Ms) {
    }
}
