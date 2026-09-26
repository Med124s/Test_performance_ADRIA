package com.loadpilot.backend.service.report;

import java.util.List;

/**
 * P1-A (prompt section 13-15) — le backend ne calculait AUCUN percentile
 * nulle part avant cette phase (verifie par recherche exhaustive du code
 * pendant P0-B) : cette classe est la toute premiere implementation reelle,
 * jamais une valeur approximative ou hardcodee.
 *
 * METHODE : "nearest rank" (rang le plus proche), la definition la plus
 * simple et la plus repandue des percentiles sur une population finie et
 * deterministe (pas d'interpolation entre deux valeurs, contrairement a la
 * methode "linear interpolation" d'Excel/NumPy - choisie ici pour sa
 * simplicite et sa reproductibilite exacte, suffisante pour un premier
 * reporting fiable, voir prompt section 14).
 *
 * Pour un percentile p (0-100) sur une population triee de taille n :
 *   rang = ceil(p / 100 * n), au moins 1, au plus n
 *   valeur = population triee [rang - 1]  (index 0-based)
 *
 * POPULATION : temps de reponse en MILLISECONDES (voir
 * ExecutionStepResult.responseTime), jamais une autre unite.
 * VALEURS NULLES : exclues de la population AVANT tri (une requete sans
 * temps de reponse mesurable n'est jamais comptee comme 0ms - un 0ms
 * fabrique fausserait p50/p95/p99).
 * POPULATION VIDE : toutes les methodes retournent null - jamais une
 * valeur inventee (0, NaN ou autre).
 * POPULATION A 1 VALEUR : min = max = moyenne = tous les percentiles =
 * cette unique valeur (cas verifie explicitement par test).
 */
public final class PercentileCalculator {

    private PercentileCalculator() {
    }

    /** Percentile p (0 &lt; p &le; 100) sur des temps de reponse en ms. Null si population vide. */
    public static Long percentile(List<Long> responseTimesMs, double p) {
        List<Long> sorted = sortedNonNull(responseTimesMs);
        if (sorted.isEmpty()) {
            return null;
        }
        int n = sorted.size();
        int rank = (int) Math.ceil(p / 100.0 * n);
        rank = Math.max(1, Math.min(n, rank));
        return sorted.get(rank - 1);
    }

    public static Long min(List<Long> responseTimesMs) {
        List<Long> sorted = sortedNonNull(responseTimesMs);
        return sorted.isEmpty() ? null : sorted.get(0);
    }

    public static Long max(List<Long> responseTimesMs) {
        List<Long> sorted = sortedNonNull(responseTimesMs);
        return sorted.isEmpty() ? null : sorted.get(sorted.size() - 1);
    }

    /** Moyenne arrondie a l'entier le plus proche (meme unite, ms) - null si population vide. */
    public static Long average(List<Long> responseTimesMs) {
        List<Long> sorted = sortedNonNull(responseTimesMs);
        if (sorted.isEmpty()) {
            return null;
        }
        double sum = 0;
        for (long v : sorted) {
            sum += v;
        }
        return Math.round(sum / sorted.size());
    }

    /**
     * P1-C — ecart-type de POPULATION (division par n, jamais n-1) : les
     * temps de reponse dont on dispose ICI sont la population COMPLETE de
     * cette Execution/ce Step (jamais un echantillon d'un ensemble plus
     * grand), donc jamais de correction de Bessel. Arrondi a l'entier le
     * plus proche (meme unite ms que les autres statistiques) - null si
     * population vide OU a une seule valeur (ecart-type d'un singleton =
     * 0, valeur reelle et non ambigue, mais deliberement retournee comme 0
     * plutot que null : contrairement a une moyenne sur ensemble vide, 0
     * est ici la vraie valeur mathematique, pas une absence de donnee).
     */
    public static Long stdDev(List<Long> responseTimesMs) {
        List<Long> sorted = sortedNonNull(responseTimesMs);
        if (sorted.isEmpty()) {
            return null;
        }
        double mean = 0;
        for (long v : sorted) {
            mean += v;
        }
        mean /= sorted.size();
        double variance = 0;
        for (long v : sorted) {
            double diff = v - mean;
            variance += diff * diff;
        }
        variance /= sorted.size();
        return Math.round(Math.sqrt(variance));
    }

    private static List<Long> sortedNonNull(List<Long> values) {
        return values.stream().filter(v -> v != null).sorted().toList();
    }
}
