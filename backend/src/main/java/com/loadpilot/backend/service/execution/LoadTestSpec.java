package com.loadpilot.backend.service.execution;

/** Parametres de charge utilises par le moteur, copies du scenario au lancement. */
public record LoadTestSpec(
        int virtualUsers,
        int rampUpSeconds,
        Integer durationSeconds,
        Integer iterations,
        int thinkTimeMs,
        /** Debit cible (requetes/s, partage entre les utilisateurs virtuels) ; null = aucun pacing. */
        Integer targetRps,
        /** Ralentissement sur 429 ; null = actif. */
        ThrottlePolicy throttle,
        /** Variation aleatoire du think time (± %) ; 0 = fixe. */
        int thinkTimeVariationPercent
) {
    public LoadTestSpec {
        if (throttle == null) throttle = ThrottlePolicy.DEFAULT;
        thinkTimeVariationPercent = Math.max(0, Math.min(100, thinkTimeVariationPercent));
    }

    public LoadTestSpec(int virtualUsers, int rampUpSeconds, Integer durationSeconds, Integer iterations, int thinkTimeMs,
            Integer targetRps, ThrottlePolicy throttle) {
        this(virtualUsers, rampUpSeconds, durationSeconds, iterations, thinkTimeMs, targetRps, throttle, 0);
    }

    public LoadTestSpec(int virtualUsers, int rampUpSeconds, Integer durationSeconds, Integer iterations, int thinkTimeMs,
            Integer targetRps) {
        this(virtualUsers, rampUpSeconds, durationSeconds, iterations, thinkTimeMs, targetRps, ThrottlePolicy.DEFAULT);
    }

    public static LoadTestSpec singlePass() {
        return new LoadTestSpec(1, 0, null, null, 0, null);
    }
}
