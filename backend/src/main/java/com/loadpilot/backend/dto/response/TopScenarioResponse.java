package com.loadpilot.backend.dto.response;

import java.math.BigDecimal;

/** Scenario du classement "Top scenarios", par nombre d'executions sur la periode. */
public record TopScenarioResponse(
        String scenarioId,
        String scenarioName,
        String applicationName,
        long executionCount,
        BigDecimal successRate,
        BigDecimal avgResponseTime
) {
}
