package com.loadpilot.backend.dto.response;

import java.time.Instant;

/** Taille de la base et purges automatiques ; databaseSizeBytes null si inconnue. */
public record StorageInfoResponse(Long databaseSizeBytes, Purge results, Purge audit) {

    /** lastRunAt null = jamais executee ; nextRunAt null = purge desactivee. */
    public record Purge(Instant lastRunAt, Long lastDeleted, String lastSummary, Instant nextRunAt) {
    }
}
