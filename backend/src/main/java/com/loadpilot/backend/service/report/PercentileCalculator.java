package com.loadpilot.backend.service.report;

import java.util.List;

/** Percentiles (rang le plus proche : rang = ceil(p / 100 * n)) et moyenne des temps de reponse. */
public final class PercentileCalculator {

    private PercentileCalculator() {
    }

    /** Percentile p (0 &lt; p &le; 100) des temps de reponse (ms) ; null si la liste est vide. */
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

    /** Moyenne arrondie (ms) ; null si la liste est vide. */
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

    private static List<Long> sortedNonNull(List<Long> values) {
        return values.stream().filter(v -> v != null).sorted().toList();
    }
}
