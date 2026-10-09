package com.loadpilot.backend.dto.request;

/**
 * Charge par defaut (Parametres > Systeme) : valeurs proposees a l'etape « Charge & resume » d'un
 * nouveau scenario. Validation dans LoadDefaultsService (memes regles et memes messages que
 * l'interface) : facultatifs = null.
 */
public record LoadDefaultsRequest(
        Integer virtualUsers,
        Integer thinkTimeMs,
        Integer rampUpSeconds,
        Integer durationSeconds,
        Integer iterations,
        Integer targetRps
) {
}
