package com.loadpilot.backend.dto.response;

/** Vue detaillee d'une seule Application (GET /api/dashboard/applications/{id})
 * - memes sous-structures que DashboardResponse, filtrees par Application. */
public record ApplicationDashboardResponse(
        ApplicationIdentityResponse application,
        ScenariosSummaryResponse scenarios,
        ExecutionsSummaryResponse executions,
        PerformanceSummaryResponse performance
) {
}
