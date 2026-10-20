package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** Think time fixe (comme avant) ou aleatoire autour de la valeur configuree, moyenne inchangee. */
class ThinkTimeTest {

    @Test
    void withoutVariation_exactlyTheConfiguredValue_asBefore() {
        for (int i = 0; i < 100; i++) {
            assertThat(ThinkTime.pick(500, 0)).isEqualTo(500);
        }
        assertThat(ThinkTime.pick(0, 50)).as("pas de think time : rien a faire varier").isZero();
        assertThat(ThinkTime.pick(500, -10)).isEqualTo(500);
    }

    @Test
    void withVariation_uniformWithinBounds_bothSides_averageKept() {
        SplittableRandom random = new SplittableRandom(42);
        Set<Long> distinct = new HashSet<>();
        long sum = 0;
        int n = 20_000;
        for (int i = 0; i < n; i++) {
            long v = ThinkTime.pick(1000, 20, random);
            assertThat(v).isBetween(800L, 1200L);
            distinct.add(v);
            sum += v;
        }
        assertThat(distinct).as("valeurs variees").hasSizeGreaterThan(300);
        assertThat(distinct.stream().anyMatch(v -> v < 900)).isTrue();
        assertThat(distinct.stream().anyMatch(v -> v > 1100)).isTrue();
        assertThat(sum / (double) n).as("moyenne ~ valeur configuree").isBetween(990.0, 1010.0);
    }

    @Test
    void variationCappedAt100Percent_neverNegative() {
        for (int i = 0; i < 1000; i++) {
            assertThat(ThinkTime.pick(100, 250)).isBetween(0L, 200L);
        }
        // Spec : variation bornee a [0, 100] des la construction.
        assertThat(new LoadTestSpec(1, 0, null, 1, 100, null, ThrottlePolicy.DEFAULT, 150).thinkTimeVariationPercent()).isEqualTo(100);
        assertThat(new LoadTestSpec(1, 0, null, 1, 100, null).thinkTimeVariationPercent()).as("compatibilite : fixe").isZero();
    }
}
