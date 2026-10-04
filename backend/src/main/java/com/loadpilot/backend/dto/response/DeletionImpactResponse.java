package com.loadpilot.backend.dto.response;

/**
 * Impact d'une suppression ou d'un archivage, affiche AVANT confirmation.
 * Pour une selection multiple, les compteurs sont cumules.
 */
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
