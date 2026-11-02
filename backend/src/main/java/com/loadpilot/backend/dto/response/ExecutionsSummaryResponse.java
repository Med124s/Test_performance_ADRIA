package com.loadpilot.backend.dto.response;

import java.math.BigDecimal;

/** Compteurs des etapes executees (une ligne de resultat par etape lancee). */
public record ExecutionsSummaryResponse(
        long totalExecutions,
        long successfulExecutions,
        long failedExecutions,
        long runningExecutions,
        long cancelledExecutions,
        long totalStepsExecuted,
        long successfulSteps,
        long failedSteps,
        BigDecimal successRate,
        BigDecimal failureRate
) {
}
