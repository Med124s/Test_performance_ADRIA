package com.loadpilot.backend.service.keycloak;

import java.time.Instant;

/** Sous-ensemble reel des champs Keycloak effectivement utilises (voir
 * https://www.keycloak.org/docs-api/latest/rest-api/#UserRepresentation) -
 * jamais l'objet JSON complet retourne tel quel (qui peut contenir des
 * attributs internes non pertinents), et jamais un mot de passe/credential. */
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
