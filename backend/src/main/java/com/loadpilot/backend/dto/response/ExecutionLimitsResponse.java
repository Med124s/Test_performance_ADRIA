package com.loadpilot.backend.dto.response;

import java.util.List;

/** Limites de Parametres > Systeme : limites modifiables (value <= ceiling) et delais en lecture seule. */
public record ExecutionLimitsResponse(List<Limit> limits, int executionTimeoutSeconds, int availabilityTimeoutSeconds,
                                      int effectiveVirtualUsersPerTest, List<String> notices) {

    /** key : virtualUsersPerTest, virtualUsersTotal, concurrentTests, maxDurationSeconds, maxIterations, maxTargetRps. */
    public record Limit(String key, String label, String unit, int value, int ceiling) {
    }
}
