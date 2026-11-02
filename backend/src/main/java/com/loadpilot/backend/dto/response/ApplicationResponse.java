package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.ApplicationStatus;
import java.time.Instant;

/** Application ; "createdBy" est le nom d'affichage du createur. */
public record ApplicationResponse(
        String id,
        String name,
        String description,
        String url,
        ApplicationStatus status,
        String statusMessage,
        Instant statusCheckedAt,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        ApplicationAuthResponse auth,
        boolean archived,
        Instant archivedAt,
        ApplicationMonitoringResponse monitoring,
        /** Proxy pour joindre l'application, sans le mot de passe. */
        ApplicationNetworkResponse network
) {
}
