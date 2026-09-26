package com.loadpilot.backend.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * Vue globale de la plateforme (GET /api/dashboard) - agregat calcule en
 * temps reel a partir des tables existantes (Application/Scenario/
 * Execution/ExecutionStepResult/Metric), aucun stockage dedie (voir Phase 11).
 *
 * P1-C — "rangeFrom"/"rangeTo" refletent la plage REELLEMENT appliquee
 * (null/null = tout l'historique, comportement identique a avant P1-C) ;
 * "executions"/"performance"/"topScenarios" sont filtres par cette plage
 * quand elle est fournie - "applications"/"scenarios" restent
 * deliberement TOUJOURS all-time (un decompte de configuration n'a pas de
 * notion temporelle pertinente pour un dashboard de performance, voir
 * DashboardServiceImpl).
 */
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
