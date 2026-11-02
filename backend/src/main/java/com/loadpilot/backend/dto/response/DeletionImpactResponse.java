package com.loadpilot.backend.dto.response;

/** Impact d'une suppression ou d'un archivage, affiche avant confirmation. */
public record DeletionImpactResponse(
        long applications,
        long scenarios,
        long steps,
        long executions,
        long scheduledExecutions,
        long runningExecutions,
        boolean hasRunningExecution
) {
}
