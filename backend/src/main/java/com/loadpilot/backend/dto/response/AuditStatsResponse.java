package com.loadpilot.backend.dto.response;

import java.util.Map;

/** Statistiques du journal d'audit ; les cles des maps sont les noms des enums. */
public record AuditStatsResponse(
        long totalActions,
        long successfulActions,
        long failedActions,
        Map<String, Long> actionsByModule,
        Map<String, Long> actionsByAction
) {
}
