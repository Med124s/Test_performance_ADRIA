package com.loadpilot.backend.service.keycloak;

import java.time.Instant;

/** Utilisateur Keycloak (champs utilises par LoadPilot). */
public record KeycloakUserRecord(
        String id,
        String username,
        String email,
        String firstName,
        String lastName,
        boolean enabled,
        Instant createdAt
) {

    /** Compte sans prenom ni nom renseignes. */
    public KeycloakUserRecord(String id, String username, String email, boolean enabled, Instant createdAt) {
        this(id, username, email, null, null, enabled, createdAt);
    }

    /** Meme compte avec un autre etat active/desactive. */
    public KeycloakUserRecord withEnabled(boolean newEnabled) {
        return new KeycloakUserRecord(id, username, email, firstName, lastName, newEnabled, createdAt);
    }
}
