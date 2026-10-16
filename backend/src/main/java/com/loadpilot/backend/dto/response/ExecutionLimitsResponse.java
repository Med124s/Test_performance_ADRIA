package com.loadpilot.backend.dto.response;

import java.util.List;

/**
 * Limites de Parametres > Systeme : limites applicatives modifiables (value <= ceiling, plafond du
 * serveur fixe par variable d'environnement) et delais en lecture seule. « Par test » n'est jamais
 * au-dessus de « au total ». notices : ce que l'enregistrement a ajuste de lui-meme (vide en lecture).
 */
public record ExecutionLimitsResponse(List<Limit> limits, int executionTimeoutSeconds, int availabilityTimeoutSeconds,
                                      int effectiveVirtualUsersPerTest, List<String> notices) {

    /** key : virtualUsersPerTest, virtualUsersTotal, concurrentTests, maxDurationSeconds, maxIterations, maxTargetRps. */
    public record Limit(String key, String label, String unit, int value, int ceiling) {
    }
}
