package com.loadpilot.backend.dto.response;

import java.util.List;

/**
 * Enveloppe de pagination generique (voir Phase 12 - AuditLog peut devenir
 * volumineux). Volontairement une structure a nous plutot que d'exposer
 * directement org.springframework.data.domain.Page (serialisation JSON
 * interne non garantie stable entre versions Spring Data).
 */
public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
