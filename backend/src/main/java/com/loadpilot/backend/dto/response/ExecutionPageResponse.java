package com.loadpilot.backend.dto.response;

import java.util.List;

/**
 * Page de la liste des executions (GET /api/executions/page) : lignes de la page courante et
 * compteurs par statut calcules avec les MEMES filtres (cartes du haut de la page Executions).
 */
public record ExecutionPageResponse(
        List<ExecutionHistoryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        Counts counts
) {
    public record Counts(long total, long queued, long running, long paused, long success, long failed, long cancelled,
                         long thresholdsExceeded) {
    }
}
