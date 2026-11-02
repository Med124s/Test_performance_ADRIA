package com.loadpilot.backend.dto.response;

/** Tableau de bord d'une application. */
public record ApplicationDashboardResponse(
        ApplicationIdentityResponse application,
        ScenariosSummaryResponse scenarios,
        ExecutionsSummaryResponse executions,
        PerformanceSummaryResponse performance
) {
}
