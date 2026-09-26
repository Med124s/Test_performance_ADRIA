package com.loadpilot.backend.dto.response;

import java.util.Map;

/** Statistiques calculees en temps reel a partir des vrais AuditLog (voir
 * AuditLogServiceImpl.getStats) - jamais de valeur fictive. actionsByModule/
 * actionsByAction sont les cles enum (name()) associees a leur nombre reel
 * d'occurrences. */
public record AuditStatsResponse(
        long totalActions,
        long successfulActions,
        long failedActions,
        Map<String, Long> actionsByModule,
        Map<String, Long> actionsByAction
) {
}
