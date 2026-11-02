package com.loadpilot.backend.dto.response;

/** Compteurs de scenarios par statut. */
public record ScenariosSummaryResponse(
        long totalScenarios,
        long activeScenarios,
        long draftScenarios
) {
}
