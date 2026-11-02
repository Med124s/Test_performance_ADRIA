package com.loadpilot.backend.dto.response;

import java.time.Instant;
import java.util.List;

/** Vue globale de la plateforme, calculee a partir des donnees existantes. */
public record DashboardResponse(
        ApplicationsSummaryResponse applications,
        ScenariosSummaryResponse scenarios,
        ExecutionsSummaryResponse executions,
        PerformanceSummaryResponse performance,
        Instant rangeFrom,
        Instant rangeTo,
        List<TopScenarioResponse> topScenarios
) {
}
