package com.loadpilot.backend.service.execution;

import java.time.Instant;
import java.util.UUID;

/** Resultat d'une requete ; httpStatus est null en cas d'echec technique. */
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
        String requestUrl,
        /** Detail complet conserve pour cette requete (echec ou echantillon), sinon null. */
        RequestDetail detail
) {
    public StepOutcome(UUID stepId, Integer httpStatus, long responseTimeMs, boolean success, String error, Instant timestamp) {
        this(stepId, httpStatus, responseTimeMs, success, error, timestamp, null, null, null, null);
    }

    public StepOutcome(UUID stepId, Integer httpStatus, long responseTimeMs, boolean success, String error, Instant timestamp,
            Integer virtualUser, Integer iteration, String requestUrl) {
        this(stepId, httpStatus, responseTimeMs, success, error, timestamp, virtualUser, iteration, requestUrl, null);
    }

    public StepOutcome withDetail(RequestDetail newDetail) {
        return new StepOutcome(stepId, httpStatus, responseTimeMs, success, error, timestamp, virtualUser, iteration, requestUrl, newDetail);
    }
}
