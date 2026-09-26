package com.loadpilot.backend.dto.response;

import java.math.BigDecimal;

/**
 * P1-C — un scenario du widget "Top scenarios" (voir DashboardResponse),
 * classe par nombre reel d'executions dans la plage temporelle demandee
 * (voir DashboardServiceImpl et ExecutionRepository#findTopScenariosByExecutionCount).
 * "successRate"/"avgResponseTime" null uniquement si non calculable (jamais
 * une valeur fabriquee).
 */
public record TopScenarioResponse(
        String scenarioId,
        String scenarioName,
        String applicationName,
        long executionCount,
        BigDecimal successRate,
        BigDecimal avgResponseTime
) {
}
