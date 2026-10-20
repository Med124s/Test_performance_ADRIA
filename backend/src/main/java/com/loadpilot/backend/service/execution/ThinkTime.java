package com.loadpilot.backend.service.execution;

import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Think time d'un utilisateur virtuel : fixe (comme avant), ou tire uniformement dans
 * [t × (1 − v %), t × (1 + v %)] pour un comportement plus realiste - les utilisateurs ne se
 * synchronisent plus sur le meme rythme. Moyenne inchangee : la charge moyenne envoyee reste la meme.
 */
public final class ThinkTime {

    private ThinkTime() {
    }

    /** Pause a appliquer (ms) ; base <= 0 ou variation <= 0 : base, exactement. */
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
