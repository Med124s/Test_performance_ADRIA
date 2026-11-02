package com.loadpilot.backend.dto.response;

import java.util.List;

/** Page de resultats. */
public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
