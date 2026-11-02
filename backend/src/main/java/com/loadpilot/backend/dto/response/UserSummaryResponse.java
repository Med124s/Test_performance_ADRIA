package com.loadpilot.backend.dto.response;

import java.time.Instant;

/** Utilisateur Keycloak avec son role applicatif. */
public record UserSummaryResponse(
        String id,
        String username,
        String email,
        String firstName,
        String lastName,
        String displayName,
        boolean enabled,
        String role,
        Instant createdAt,
        Instant lastLoginAt,
        long executionCount
) {
}
