package com.loadpilot.backend.dto.response;

/** Limites de charge du serveur (lecture seule). */
public record SystemConfigResponse(
        int maxVirtualUsersPerExecution,
        int maxGlobalVirtualUsers,
        int maxConcurrentExecutions,
        int executionTimeoutSeconds,
        int availabilityTimeoutSeconds,
        long schedulerPollIntervalMs
) {
}
