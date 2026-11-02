package com.loadpilot.backend.dto.response;

import java.util.List;

/** Page de la liste des executions et compteurs par statut (memes filtres). */
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
