package com.loadpilot.backend.service.execution;

import java.time.Instant;
import java.util.UUID;

/** Resultat REEL (jamais simule) de l'execution d'un Step. httpStatus est
 * null en cas d'echec technique (aucune reponse HTTP recue). */
public record StepOutcome(
        UUID stepId,
        Integer httpStatus,
        long responseTimeMs,
        boolean success,
        String error,
        Instant timestamp,
        /** Utilisateur virtuel (0..n-1), iteration (0..) et URL finale appelee (tracabilite). */
        Integer virtualUser,
        Integer iteration,
        String requestUrl
) {
    public StepOutcome(UUID stepId, Integer httpStatus, long responseTimeMs, boolean success, String error, Instant timestamp) {
        this(stepId, httpStatus, responseTimeMs, success, error, timestamp, null, null, null);
    }
}
