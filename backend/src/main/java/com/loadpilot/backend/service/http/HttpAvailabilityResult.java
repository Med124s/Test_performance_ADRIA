package com.loadpilot.backend.service.http;

/**
 * Resultat brut d'un test de disponibilite reel.
 *
 * "success" = une vraie reponse HTTP a ete recue (quel que soit son code -
 * 500 compris) ; false = aucune reponse (reseau/timeout/hote injoignable).
 * C'est a l'appelant (ApplicationService) de traduire ça en
 * CONNECTED/FAILED/ERROR.
 */
public record HttpAvailabilityResult(
        boolean success,
        Integer httpStatus,
        long responseTimeMs,
        String errorMessage
) {
}
