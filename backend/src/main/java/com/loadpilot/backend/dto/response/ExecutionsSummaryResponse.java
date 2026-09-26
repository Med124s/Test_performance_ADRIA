package com.loadpilot.backend.dto.response;

import java.math.BigDecimal;

/**
 * totalStepsExecuted/successfulSteps/failedSteps comptent les ETAPES
 * REELLEMENT EXECUTEES (une ligne ExecutionStepResult par etape reellement
 * lancee) - PAS le nombre d'etapes CONFIGUREES sur les Scenarios (voir
 * Step). La Phase 9 arrete une Execution des le premier echec
 * (stop-on-failure) : totalStepsExecuted peut donc etre strictement
 * inferieur a la somme des Steps configures sur les Scenarios executes.
 *
 * successRate/failureRate (%) sont calcules sur totalStepsExecuted (jamais
 * sur un nombre d'etapes configurees) ; null si totalStepsExecuted == 0 -
 * aucune etape executee, le taux est indefini, jamais invente a 0 ou 100.
 */
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
