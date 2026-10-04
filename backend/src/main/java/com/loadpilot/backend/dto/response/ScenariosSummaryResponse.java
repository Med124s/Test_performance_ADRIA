package com.loadpilot.backend.dto.response;

/** ACTIVE/DRAFT (brouillon) sont des statuts de cycle de vie (voir ScenarioStatus),
 * jamais un resultat d'execution. */
public record ScenariosSummaryResponse(
        long totalScenarios,
        long activeScenarios,
        long draftScenarios
) {
}
