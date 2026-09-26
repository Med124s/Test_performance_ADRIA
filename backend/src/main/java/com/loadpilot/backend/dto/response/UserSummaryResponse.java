package com.loadpilot.backend.dto.response;

import java.time.Instant;

/**
 * Vue minimale d'un utilisateur Keycloak reel (GET /api/users) - jamais le
 * mot de passe ni aucun credential. "role" est le SEUL des 3 roles
 * applicatifs (voir enums.AppRole) reellement present dans les
 * realm_access.roles de l'utilisateur cote Keycloak ; "null" si aucun des
 * 3 n'est assigne (jamais une valeur inventee par defaut).
 */
public record UserSummaryResponse(
        String id,
        String username,
        String email,
        boolean enabled,
        String role,
        Instant createdAt
) {
}
