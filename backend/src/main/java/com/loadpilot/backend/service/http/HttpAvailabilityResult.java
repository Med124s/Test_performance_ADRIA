package com.loadpilot.backend.service.http;

/** Resultat d'un test de connectivite ; "success" = une reponse HTTP a ete recue, quel que soit son code. */
public record HttpAvailabilityResult(
        boolean success,
        Integer httpStatus,
        long responseTimeMs,
        String errorMessage
) {
}
