package com.loadpilot.backend.dto.response;

import java.time.Instant;

/**
 * Vue minimale d'un utilisateur Keycloak reel (GET /api/users) - jamais le
 * mot de passe ni aucun credential. "role" est le SEUL des 3 roles
 * applicatifs (voir enums.AppRole) reellement present dans les
 * realm_access.roles de l'utilisateur cote Keycloak ; "null" si aucun des
 * 3 n'est assigne (jamais une valeur inventee par defaut).
 *
 * displayName : "prenom nom" si renseignes dans Keycloak, sinon le username.
 * lastLoginAt : derniere connexion lue dans les evenements LOGIN de Keycloak
 * (null si aucune connexion enregistree ou si les evenements sont indisponibles).
 * executionCount : executions lancees par cet utilisateur dans LoadPilot.
 */
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
