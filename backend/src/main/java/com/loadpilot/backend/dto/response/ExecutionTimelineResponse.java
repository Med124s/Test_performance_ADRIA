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
        /** Message quand aucune analyse n'est possible (aucune requete enregistree). */
        String message
) {
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
