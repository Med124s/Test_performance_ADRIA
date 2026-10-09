package com.loadpilot.backend.dto.response;

import java.time.Instant;

/**
 * Parametres > Systeme : taille actuelle de la base et purges automatiques (conservation des
 * resultats, journal d'audit). databaseSizeBytes null si la base ne sait pas la donner.
 */
public record StorageInfoResponse(Long databaseSizeBytes, Purge results, Purge audit) {

    /**
     * lastRunAt null = jamais passee depuis l'installation ; lastDeleted = elements supprimes au
     * dernier passage ; lastSummary = bilan lisible ; nextRunAt null = purge automatique desactivee.
     */
    public record Purge(Instant lastRunAt, Long lastDeleted, String lastSummary, Instant nextRunAt) {
    }
}
