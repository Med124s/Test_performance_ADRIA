package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.StressOutcome;
import java.util.List;

/**
 * Resultat d'un test de stress. "outcome" null : test arrete autrement (annulation, limite memoire). Champs du point de
 * rupture (criteria a refusalPercent) renseignes pour BREAKPOINT : niveau de charge et mesures au debut de l'anomalie.
 */
public record StressResultResponse(
        StressOutcome outcome,
        /** NO_BREAKPOINT : limite de LoadPilot atteinte (TARGET_RPS, VIRTUAL_USERS, DURATION). */
        String limit,
        String message,
        Integer peakVirtualUsers,
        Double peakOfferedRps,
        /** RESPONSE_TIME, REFUSALS, THROUGHPUT. */
        List<String> criteria,
        /** Debut de l'anomalie et confirmation, en secondes depuis le debut du test. */
        Integer onsetSeconds,
        Integer detectedSeconds,
        Integer virtualUsers,
        Double offeredRps,
        Double throughputRps,
        Long p95Ms,
        Long baselineP95Ms,
        Double refusalPercent,
        /** Vitesse de montee utilisee (req/s gagnees chaque seconde) ; le debut de la rupture est connu a 5 x cette vitesse pres. */
        Integer rampRpsPerSecond
) {
}
