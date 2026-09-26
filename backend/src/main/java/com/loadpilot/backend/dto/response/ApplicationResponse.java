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
        String createdBy,
        Instant createdAt,
        Instant updatedAt
) {
}
