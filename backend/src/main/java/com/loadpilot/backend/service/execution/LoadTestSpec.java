package com.loadpilot.backend.service.execution;

/**
 * Parametres de charge REELLEMENT utilises par le moteur (voir
 * HttpClientExecutionEngine) - copie immuable des colonnes de charge du
 * Scenario au moment du lancement (voir ExecutionTransactionHelper).
 *
 * Comportement par defaut (virtualUsers=1, rampUpSeconds=0,
 * durationSeconds=null, iterations=null, thinkTimeMs=0) : EXACTEMENT le
 * comportement historique (Phase 9) - un seul utilisateur virtuel, une
 * seule passe des Steps, sans delai. Aucune regression sur les scenarios
 * existants qui n'ont jamais configure de charge.
 */
public record LoadTestSpec(
        int virtualUsers,
        int rampUpSeconds,
        Integer durationSeconds,
        Integer iterations,
        int thinkTimeMs,
        /** Master prompt final (Lot A) — debit cible optionnel (requetes/s,
         * approximatif, partage entre tous les VUs de l'Execution). null =
         * aucun pacing, comportement historique inchange. */
        Integer targetRps,
        /** Ralentissement sur 429 (voir ThrottlePolicy) ; null = defaut (actif). */
        ThrottlePolicy throttle,
        /** Variation aleatoire du think time (± %, scenario et etapes, voir ThinkTime) ; 0 = fixe, comme avant. */
        int thinkTimeVariationPercent
) {
    public LoadTestSpec {
        if (throttle == null) throttle = ThrottlePolicy.DEFAULT;
        thinkTimeVariationPercent = Math.max(0, Math.min(100, thinkTimeVariationPercent));
    }

    /** Compatibilite : think time fixe. */
    public LoadTestSpec(int virtualUsers, int rampUpSeconds, Integer durationSeconds, Integer iterations, int thinkTimeMs,
            Integer targetRps, ThrottlePolicy throttle) {
        this(virtualUsers, rampUpSeconds, durationSeconds, iterations, thinkTimeMs, targetRps, throttle, 0);
    }

    /** Compatibilite : ralentissement sur 429 par defaut (actif). */
    public LoadTestSpec(int virtualUsers, int rampUpSeconds, Integer durationSeconds, Integer iterations, int thinkTimeMs,
            Integer targetRps) {
        this(virtualUsers, rampUpSeconds, durationSeconds, iterations, thinkTimeMs, targetRps, ThrottlePolicy.DEFAULT);
    }

    public static LoadTestSpec singlePass() {
        return new LoadTestSpec(1, 0, null, null, 0, null);
    }
}
