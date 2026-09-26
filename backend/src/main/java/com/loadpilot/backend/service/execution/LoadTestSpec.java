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
        Integer targetRps
) {
    public static LoadTestSpec singlePass() {
        return new LoadTestSpec(1, 0, null, null, 0, null);
    }
}
