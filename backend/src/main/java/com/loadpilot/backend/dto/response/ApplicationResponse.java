package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.ApplicationStatus;
import java.time.Instant;

/**
 * "createdBy" expose le nom d'affichage du createur (username, ou a defaut
 * son identifiant Keycloak) - jamais l'entite AppUser elle-meme, jamais de
 * secret/JWT.
 */
public record ApplicationResponse(
        String id,
        String name,
        String description,
        String url,
        ApplicationStatus status,
        String type,
        String authMethod,
        /** true si un token est enregistre - jamais sa valeur (voir
         * Application.authToken/ApplicationMapper : ecriture seule, comme
         * un secret). */
        boolean hasAuthToken,
        String createdBy,
        Instant createdAt,
        Instant updatedAt
) {
}
