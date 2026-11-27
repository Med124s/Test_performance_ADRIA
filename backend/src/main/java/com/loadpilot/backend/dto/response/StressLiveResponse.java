package com.loadpilot.backend.dto.response;

/**
 * Test de stress en cours (suivi en direct). phase : WARMUP (echauffement), BASELINE (mesure de reference),
 * RAMP (montee), HOLD (limite de LoadPilot atteinte, maintenue avant de conclure).
 */
public record StressLiveResponse(
        String phase,
        long elapsedSeconds,
        /** Fin de l'echauffement et de la baseline, en secondes depuis le debut du test. */
        int warmupEndSeconds,
        int baselineEndSeconds,
        double offeredRps,
        double peakOfferedRps,
        int virtualUsers,
        /** P95 de la baseline, null tant qu'elle n'est pas mesuree. */
        Long baselineP95Ms,
        /** HOLD : limite atteinte (TARGET_RPS, VIRTUAL_USERS). */
        String holdingLimit,
        int maxVirtualUsers,
        int maxRps,
        int maxDurationSeconds,
        /** Vitesse de montee (req/s gagnees chaque seconde). */
        int rampRpsPerSecond
) {
}
