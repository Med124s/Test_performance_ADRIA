package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.ExecutionStatus;
import java.time.Instant;

/**
 * Vue allegee d'une Execution (P0-A), pensee pour du polling frequent
 * pendant un test de charge en cours - jamais le detail complet (pas de
 * liste de resultats par etape, potentiellement tres volumineuse avec
 * plusieurs utilisateurs virtuels).
 *
 * "progressPercent" n'est JAMAIS invente : calcule uniquement quand
 * reellement possible (duree ou iterations configurees), sinon null -
 * voir ExecutionServiceImpl.buildStatusResponse.
 */
public record ExecutionStatusResponse(
        String id,
        ExecutionStatus status,
        Instant startedAt,
        Instant finishedAt,
        Long duration,
        Integer virtualUsers,
        Integer totalRequests,
        Integer successfulRequests,
        Integer failedRequests,
        Integer progressPercent
) {
}
