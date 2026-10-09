package com.loadpilot.backend.dto.request;

/** Limites applicatives (Parametres > Systeme > Limites) ; chacune entre 1 et son plafond du serveur. */
public record ExecutionLimitsRequest(
        Integer virtualUsersPerTest,
        Integer virtualUsersTotal,
        Integer concurrentTests,
        Integer maxDurationSeconds,
        Integer maxIterations,
        Integer maxTargetRps
) {
}
