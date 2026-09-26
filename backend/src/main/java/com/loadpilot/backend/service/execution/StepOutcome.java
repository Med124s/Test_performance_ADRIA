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
        Instant timestamp
) {
}
