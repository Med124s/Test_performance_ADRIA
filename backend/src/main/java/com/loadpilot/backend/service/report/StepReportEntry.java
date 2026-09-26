package com.loadpilot.backend.service.report;

import com.loadpilot.backend.enums.HttpMethod;
import java.math.BigDecimal;

/**
 * Statistiques d'UN Step, agregees sur toutes ses occurrences reelles au
 * sein d'une Execution (potentiellement plusieurs si virtualUsers/
 * iterations > 1 - voir prompt P1-A section 20). Memes formules/unites que
 * ExecutionStatistics, calculees sur le seul sous-ensemble de resultats de
 * ce Step.
 */
public record StepReportEntry(
        String stepId,
        String stepName,
        HttpMethod method,
        String url,
        int total,
        int success,
        int failed,
        Long avgResponseTime,
        Long minResponseTime,
        Long maxResponseTime,
        Long p95,
        Long p99,
        /** P1-C — ecart-type de population (voir PercentileCalculator#stdDev). */
        Long stdDevResponseTime,
        BigDecimal errorRate
) {
}
