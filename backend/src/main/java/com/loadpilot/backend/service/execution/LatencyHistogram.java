package com.loadpilot.backend.service.execution;

/** Temps de reponse ranges par classes geometriques (+15 % d'une borne a la suivante) : percentile approche en memoire fixe. */
final class LatencyHistogram {

    /** Rapport entre deux bornes successives : percentile exact a 15 % pres. */
    static final double GROWTH = 1.15;
    /** Plus grand temps distingue (ms) ; au-dela, tout tombe dans la derniere classe. */
    static final long MAX_MS = 600_000;
    static final int BUCKETS = bucketOf(MAX_MS) + 1;

    private LatencyHistogram() {
    }

    /** Classe d'un temps : 0 pour <= 1 ms, sinon la plus petite classe dont la borne haute est >= ms. */
    static int bucketOf(long ms) {
        if (ms <= 1) return 0;
        int bucket = (int) Math.ceil(Math.log(Math.min(ms, MAX_MS)) / Math.log(GROWTH));
        // Arrondi flottant : la borne haute doit couvrir ms.
        while (upperBoundMs(bucket) < Math.min(ms, MAX_MS)) bucket++;
        return bucket;
    }

    /** Borne haute (ms) de la classe. */
    static long upperBoundMs(int bucket) {
        return (long) Math.floor(Math.pow(GROWTH, bucket));
    }

    /** Percentile (rang le plus proche) : borne haute de la classe qui l'atteint ; null si aucun temps. */
    static Long percentile(long[] counts, double percent) {
        long total = 0;
        for (long c : counts) total += c;
        if (total == 0) return null;
        long rank = Math.max(1, (long) Math.ceil(percent / 100.0 * total));
        long seen = 0;
        for (int i = 0; i < counts.length; i++) {
            seen += counts[i];
            if (seen >= rank) return upperBoundMs(i);
        }
        return upperBoundMs(counts.length - 1);
    }
}
