package com.loadpilot.backend.service.keycloak;

/** Evenement de securite lu dans Keycloak ; typedUsername = nom saisi a la connexion. */
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
