package com.loadpilot.backend.service.execution;

import com.loadpilot.backend.enums.StressOutcome;
import java.util.List;
import java.util.Objects;

/**
 * Point de rupture consolide sur plusieurs essais du meme test de stress (serie d'essais, ou executions comparees).
 * Seuls les essais concluants comptent : rupture (BREAKPOINT) ou aucune rupture jusqu'a une limite (NO_BREAKPOINT).
 * « Confirme » : au moins deux essais, tous en rupture, avec un ecart (max - min) / moyenne d'au plus
 * SERIES_MAX_SPREAD_PERCENT.
 */
public record StressSeriesSummary(Verdict verdict, int conclusiveRuns, int ruptures, Double meanRps, Double minRps, Double maxRps,
        Double spreadPercent, Integer rampRpsPerSecond, Integer precisionRps, String message) {

    public enum Verdict {
        /** Au moins deux ruptures, toutes proches. */
        CONFIRMED,
        /** Au moins deux ruptures, trop ecartees. */
        DISPERSED,
        /** Un seul essai en rupture, ou des essais sans rupture parmi eux. */
        NOT_CONFIRMED,
        /** Aucun essai en rupture jusqu'aux limites. */
        NO_BREAKPOINT,
        /** Vitesses de montee differentes : points de rupture non comparables. */
        NOT_COMPARABLE,
        /** Aucun essai concluant. */
        NONE
    }

    /** Un essai : resultat du stress, debit offert au debut de la rupture et au plus haut de la montee, vitesse utilisee. */
    public record Run(StressOutcome outcome, Double breakpointOfferedRps, Double peakOfferedRps, Integer rampRpsPerSecond) {

        boolean conclusive() {
            return outcome == StressOutcome.BREAKPOINT || outcome == StressOutcome.NO_BREAKPOINT;
        }

        boolean rupture() {
            return outcome == StressOutcome.BREAKPOINT && breakpointOfferedRps != null;
        }
    }

    public static StressSeriesSummary of(List<Run> runs) {
        List<Run> conclusive = runs.stream().filter(Run::conclusive).toList();
        List<Integer> ramps = conclusive.stream().map(Run::rampRpsPerSecond).map(StressSettings::rampOrDefault).distinct().toList();
        if (conclusive.isEmpty()) {
            return new StressSeriesSummary(Verdict.NONE, 0, 0, null, null, null, null, null, null,
                    "Aucun essai concluant : pas de point de rupture consolidé.");
        }
        if (ramps.size() > 1) {
            return new StressSeriesSummary(Verdict.NOT_COMPARABLE, conclusive.size(), 0, null, null, null, null, null, null,
                    "Vitesses de montée différentes (" + String.join(", ", ramps.stream().map(r -> "+" + r).toList())
                            + " req/s par seconde) : points de rupture non comparables.");
        }
        int ramp = ramps.get(0);
        int precision = StressSettings.precisionRps(ramp);
        List<Double> ruptures = conclusive.stream().filter(Run::rupture).map(Run::breakpointOfferedRps).toList();
        int n = conclusive.size();

        if (ruptures.isEmpty()) {
            Double reached = conclusive.stream().map(Run::peakOfferedRps).filter(Objects::nonNull).min(Double::compare).orElse(null);
            return new StressSeriesSummary(Verdict.NO_BREAKPOINT, n, 0, null, null, null, null, ramp, precision,
                    "Aucune rupture" + (reached != null ? " jusqu'à " + rps(reached) : "") + " sur " + essais(n) + ".");
        }
        double min = ruptures.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        double max = ruptures.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        double mean = ruptures.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double spread = mean > 0 ? (max - min) / mean * 100 : 0;
        // Meme debut de fenetre a chaque essai : une seule valeur, pas un intervalle.
        String range = min == max ? "à " + rps(min) : "entre " + ExecutionVerdict.format(min) + " et " + rps(max);
        String precisionNote = "précision ± " + rps(precision);

        if (ruptures.size() < n) {
            Double reached = conclusive.stream().filter(r -> !r.rupture()).map(Run::peakOfferedRps).filter(Objects::nonNull)
                    .min(Double::compare).orElse(null);
            int without = n - ruptures.size();
            return new StressSeriesSummary(Verdict.NOT_CONFIRMED, n, ruptures.size(), mean, min, max, spread, ramp, precision,
                    "Point de rupture non confirmé : rupture sur " + ruptures.size() + " essai(s) sur " + n
                            + (ruptures.size() > 1 ? " (" + range + ")" : " (vers " + rps(min) + ")")
                            + ", aucune rupture" + (reached != null ? " jusqu'à " + rps(reached) : "") + " sur " + essais(without) + ".");
        }
        if (ruptures.size() == 1) {
            return new StressSeriesSummary(Verdict.NOT_CONFIRMED, n, 1, mean, min, max, null, ramp, precision,
                    "Point de rupture non confirmé : un seul essai concluant (rupture vers " + rps(min) + ", " + precisionNote + ").");
        }
        if (min == max) {
            return new StressSeriesSummary(Verdict.CONFIRMED, n, n, mean, min, max, spread, ramp, precision,
                    "Point de rupture confirmé à " + rps(min) + " sur les " + essais(n) + " (même valeur à chaque essai, "
                            + precisionNote + ").");
        }
        if (spread <= StressSettings.SERIES_MAX_SPREAD_PERCENT) {
            return new StressSeriesSummary(Verdict.CONFIRMED, n, n, mean, min, max, spread, ramp, precision,
                    "Point de rupture confirmé autour de " + rps(mean) + ", " + range + " sur " + essais(n) + " (écart de "
                            + ExecutionVerdict.format(spread) + " %, " + precisionNote + ").");
        }
        return new StressSeriesSummary(Verdict.DISPERSED, n, n, mean, min, max, spread, ramp, precision,
                "Résultats dispersés : point de rupture non confirmé, " + range + " sur " + essais(n) + " (écart de "
                        + ExecutionVerdict.format(spread) + " %, au-delà de " + ExecutionVerdict.format(StressSettings.SERIES_MAX_SPREAD_PERCENT)
                        + " %).");
    }

    private static String rps(double value) {
        return ExecutionVerdict.format(value) + " req/s";
    }

    private static String essais(int n) {
        return n + (n > 1 ? " essais" : " essai");
    }
}
