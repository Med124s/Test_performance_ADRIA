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
        int thinkTimeVariationPercent,
        /** Mode stress : limites de la montee automatique (les reglages ci-dessus sont alors ignores) ; null = mode manuel. */
        StressRamp.Limits stress
) {
    public LoadTestSpec {
        if (throttle == null) throttle = ThrottlePolicy.DEFAULT;
        thinkTimeVariationPercent = Math.max(0, Math.min(100, thinkTimeVariationPercent));
    }

    public LoadTestSpec(int virtualUsers, int rampUpSeconds, Integer durationSeconds, Integer iterations, int thinkTimeMs,
            Integer targetRps, ThrottlePolicy throttle, int thinkTimeVariationPercent) {
        this(virtualUsers, rampUpSeconds, durationSeconds, iterations, thinkTimeMs, targetRps, throttle, thinkTimeVariationPercent,
                null);
    }

    /**
     * Mode stress : aucun reglage de charge saisi. Think time global ignore (celui des etapes reste, avec sa variation),
     * ralentissement sur 429 desactive : un 429 est un refus, compte tout de suite.
     */
    public static LoadTestSpec stress(StressRamp.Limits limits, int thinkTimeVariationPercent) {
        return new LoadTestSpec(limits.maxVirtualUsers(), 0, null, null, 0, null, ThrottlePolicy.DISABLED,
                thinkTimeVariationPercent, limits);
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
