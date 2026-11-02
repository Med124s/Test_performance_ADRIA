package com.loadpilot.backend.service.execution;

import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/** Think time d'un utilisateur virtuel : fixe, ou tire uniformement dans [t x (1 - v %), t x (1 + v %)]. */
public final class ThinkTime {

    private ThinkTime() {
    }

    /** Pause a appliquer (ms). */
    public static long pick(long baseMs, int variationPercent) {
        return pick(baseMs, variationPercent, ThreadLocalRandom.current());
    }

    /** "random" : generateur fourni (tests). */
    static long pick(long baseMs, int variationPercent, RandomGenerator random) {
        if (baseMs <= 0 || variationPercent <= 0) return baseMs;
        long delta = baseMs * Math.min(100, variationPercent) / 100;
        if (delta == 0) return baseMs;
        return baseMs - delta + random.nextLong(2 * delta + 1);
    }
}
