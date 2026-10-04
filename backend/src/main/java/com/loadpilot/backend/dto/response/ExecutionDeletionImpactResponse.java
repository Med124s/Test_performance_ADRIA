package com.loadpilot.backend.dto.response;

/**
 * Ce que supprime definitivement une suppression d'executions : les executions, leurs resultats
 * de requetes et leurs metriques. "active" = executions en attente, en cours ou en pause (jamais
 * supprimees : elles sont ignorees, les autres sont supprimees).
 */
public record ExecutionDeletionImpactResponse(
        long executions,
        long results,
        long metrics,
        long active
) {
}
