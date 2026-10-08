package com.loadpilot.backend.service.keycloak;

/**
 * Evenement de securite lu dans Keycloak (GET /admin/realms/{realm}/events).
 * typedUsername : nom saisi a la connexion (details.username), utile pour un
 * echec sur un compte inconnu. Jamais de mot de passe : Keycloak ne l'enregistre pas.
 */
public record KeycloakSecurityEvent(
        String id,
        String type,
        long time,
        String userId,
        String typedUsername,
        String ipAddress,
        String error
) {
}
