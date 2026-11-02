package com.loadpilot.backend.dto.response;

/** Ce que supprime une suppression d'executions ; "active" = executions en cours, ignorees. */
public record ExecutionDeletionImpactResponse(
        long executions,
        long results,
        long metrics,
        long active
) {
}
