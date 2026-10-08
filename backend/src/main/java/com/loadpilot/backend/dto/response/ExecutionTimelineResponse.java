package com.loadpilot.backend.dto.response;

import java.util.List;

/**
 * Analyse dans le temps d'une execution (GET /api/executions/{id}/timeline) : series alignees sur
 * les memes instants "t" (debut de chaque intervalle, en secondes depuis le debut du test), une
 * entree par intervalle de "bucketSeconds" secondes. Une valeur null = pas de donnee a cet instant
 * (intervalle entierement en pause, ou aucune requete pour le P95).
 *
 * source : RECORDED (utilisateurs actifs enregistres pendant l'execution), DERIVED (deduits des
 * requetes : premiere a derniere requete de chaque VU), NONE (aucune requete).
 */
public record ExecutionTimelineResponse(
        String source,
        boolean virtualUsersAvailable,
        int bucketSeconds,
        long durationMs,
        List<Integer> t,
        List<Integer> activeVirtualUsers,
        List<Integer> requests,
        List<Double> throughput,
        List<Long> avgMs,
        List<Long> p95Ms,
        List<Integer> errors,
        /** Part de l'intervalle passee en pause (0..1). */
        List<Double> pausedRatio,
        List<StepSeries> steps,
        List<String> errorTypes,
        List<ErrorPoint> errorPoints,
        List<PauseRange> pauses,
        List<Insight> insights,
        /** Tableau « Évolution des étapes pendant le test » (null si aucune requete). */
        StepEvolution stepEvolution,
        /** CPU / RAM du processus de l'application testee sur le meme axe "t" ; null sans monitoring. */
        ResourceSeries resources,
        /** Debut du test (epoch ms) : t = 0. */
        long startEpochMs,
        /** Intervalle de la requete la plus rapide / la plus lente (memes requetes que les cartes Minimum / Maximum). */
        ExtremeMarker fastest,
        ExtremeMarker slowest,
        /** Message quand aucune analyse n'est possible (aucune requete enregistree). */
        String message
) {
    /**
     * Comparaison debut / fin du test par etape (voir StepEvolutionAnalyzer). Secondes en temps
     * actif (pauses retirees) ; comparable = false : message explique pourquoi (test trop court...).
     */
    public record StepEvolution(boolean comparable, String message, double stableSeconds, double startFromSeconds,
            double startToSeconds, double endFromSeconds, double endToSeconds, List<StepTrend> steps) {
    }

    /**
     * Mesures du processus teste alignees sur "t" : moyenne des mesures tombant dans chaque intervalle,
     * null sans mesure (une mesure toutes les intervalSeconds : la courbe relie les points).
     * cpuPercent en % d'un coeur ; memoryMb en Mo (memoryMetric : metrique lue).
     */
    public record ResourceSeries(String source, int intervalSeconds, String memoryMetric, String message, int sampleCount,
            List<Double> cpuPercent, List<Double> memoryMb) {
    }

    /** Repere d'une requete extreme : indice de l'intervalle, temps de reponse, seconde depuis le debut. */
    public record ExtremeMarker(int bucket, long responseTimeMs, double atSeconds) {
    }

    /** trend : DEGRADED, WATCH, STABLE, INSUFFICIENT (pas assez de requetes pour comparer). */
    public record StepTrend(String key, String name, String method, String trend, Long startP95Ms, Long endP95Ms,
            int startRequests, int endRequests, int totalRequests, Long deltaMs, Double deltaPercent) {
    }

    public record StepSeries(String key, String name, String method, List<Integer> requests, List<Double> throughput,
            List<Long> p95Ms, Long overallP95Ms) {
    }

    /** Erreurs d'un intervalle, pour une etape et un type d'erreur (indices dans steps / errorTypes). */
    public record ErrorPoint(int bucket, int step, int type, int count) {
    }

    public record PauseRange(double fromSeconds, double toSeconds) {
    }

    /** kind : PLATEAU, NO_PLATEAU, DEGRADATION, ERRORS, INFO. */
    public record Insight(String kind, String text) {
    }
}
