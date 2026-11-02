package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.HttpMethod;
import java.time.Instant;

/** Resultat d'une requete ; "url" est l'URL reellement appelee. */
public record ExecutionStepResultResponse(
        String stepId,
        String stepName,
        HttpMethod method,
        String url,
        Integer httpStatus,
        Long responseTime,
        Boolean success,
        String error,
        Instant timestamp,
        /** Utilisateur virtuel (0..n-1), iteration (0..) et URL finale reellement appelee. */
        Integer virtualUser,
        Integer iteration,
        String requestUrl
) {
}
