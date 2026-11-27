package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class StressRampTest {

    private static final long START = 1_000_000_000L;

    /** Reservoir simule ; "grows" = false : les demandes d'ajout sont ignorees (taille fixe). */
    private static final class FakePool implements StressRamp.Pool {
        int size;
        final boolean grows;

        FakePool(int size, boolean grows) {
            this.size = size;
            this.grows = grows;
        }

        @Override
        public int virtualUsers() {
            return size;
        }

        @Override
        public void add(int count) {
            if (grows) size += count;
        }
    }

    /** Montee pilotee seconde par seconde : meme horloge simulee pour la porte et les compteurs. */
    private static final class Harness {
        final AtomicLong clock = new AtomicLong(START);
        final LiveStats live = new LiveStats(List.of(), clock::get);
        final PacingGate gate = PacingGate.adjustable(StressSettings.BASELINE_RPS, clock::get);
        final FakePool pool;
        final StressRamp ramp;

        Harness(StressRamp.Limits limits, int initialVirtualUsers, boolean poolGrows) {
            pool = new FakePool(initialVirtualUsers, poolGrows);
            ramp = new StressRamp(limits, gate, live, pool);
        }

        void tick(long second) {
            clock.set(START + TimeUnit.SECONDS.toNanos(second));
            ramp.tick(second);
        }
    }

    @Test
    void offeredRate_isFlatDuringWarmupAndBaseline_thenRisesOnePerSecond_upToTheLimit() {
        long rampStart = StressSettings.WARMUP_SECONDS + StressSettings.BASELINE_SECONDS;
        assertThat(StressRamp.offeredRpsAt(0, 1, 500)).isEqualTo(StressSettings.BASELINE_RPS);
        assertThat(StressRamp.offeredRpsAt(rampStart - 1, 1, 500)).isEqualTo(StressSettings.BASELINE_RPS);
        assertThat(StressRamp.offeredRpsAt(rampStart, 1, 500)).isEqualTo(StressSettings.BASELINE_RPS + 1.0);
        assertThat(StressRamp.offeredRpsAt(rampStart + 9, 1, 500)).isEqualTo(StressSettings.BASELINE_RPS + 10.0);
        assertThat(StressRamp.offeredRpsAt(rampStart + 10_000, 1, 500)).isEqualTo(500.0);
    }

    @Test
    void offeredRate_risesAtTheScenarioRampStep_afterAnUnchangedBaseline() {
        long rampStart = StressSettings.WARMUP_SECONDS + StressSettings.BASELINE_SECONDS;
        assertThat(StressRamp.offeredRpsAt(rampStart - 1, 20, 5000)).as("baseline inchangee").isEqualTo(StressSettings.BASELINE_RPS);
        assertThat(StressRamp.offeredRpsAt(rampStart, 20, 5000)).isEqualTo(StressSettings.BASELINE_RPS + 20.0);
        assertThat(StressRamp.offeredRpsAt(rampStart + 9, 20, 5000)).isEqualTo(StressSettings.BASELINE_RPS + 200.0);
        // 5 000 req/s en ~4 min a +20 au lieu de ~83 min a +1.
        assertThat(StressRamp.offeredRpsAt(rampStart + 249, 20, 5000)).isEqualTo(5000.0);
        assertThat(StressSettings.precisionRps(20)).isEqualTo(100);
    }

    @Test
    void theRampStepOfTheLimits_drivesTheGate() {
        Harness h = new Harness(new StressRamp.Limits(1000, 5000, 3600, 30), 1, false);
        long rampStart = StressSettings.WARMUP_SECONDS + StressSettings.BASELINE_SECONDS;
        for (long s = 0; s <= rampStart + 2; s++) h.tick(s);
        assertThat(h.ramp.offeredRps()).isEqualTo(StressSettings.BASELINE_RPS + 90.0);
        assertThat(new StressRamp.Limits(200, 500, 3600).rampRpsPerSecond()).as("vitesse par defaut")
                .isEqualTo(StressSettings.DEFAULT_RAMP_RPS_PER_SECOND);
    }

    @Test
    void growth_followsTheLargerOfLittlesLawAndTheMeasuredDeficit() {
        assertThat(StressRamp.growth(0, 10, 20, 0, 200)).as("aucun besoin").isEqualTo(new StressRamp.Growth(0, false));
        assertThat(StressRamp.growth(10, 200, 20, 400, 200)).as("deja a la limite").isEqualTo(new StressRamp.Growth(0, false));
        // 10 VU font 20 req/s (2 chacun) : 4 creneaux manques par seconde -> 2 VU de plus.
        assertThat(StressRamp.growth(4, 10, 20, 0, 200)).isEqualTo(new StressRamp.Growth(2, false));
        // Loi de Little : 35 VU necessaires, 20 deja la -> 15, plus que le deficit (2).
        assertThat(StressRamp.growth(4, 20, 40, 35, 200)).isEqualTo(new StressRamp.Growth(15, false));
        // Deficit de 100 req/s : 50 VU necessaires, croissance bornee a +100 % (10) : LoadPilot en retard.
        assertThat(StressRamp.growth(100, 10, 20, 0, 200)).isEqualTo(new StressRamp.Growth(10, true));
        // Petit reservoir : au moins MIN_POOL_GROWTH par seconde.
        assertThat(StressRamp.growth(0, 1, 2, 13, 200)).isEqualTo(new StressRamp.Growth(StressSettings.MIN_POOL_GROWTH, true));
        // Aucun debit mesure encore : un VU par creneau manque.
        assertThat(StressRamp.growth(3, 1, 0, 0, 200)).isEqualTo(new StressRamp.Growth(3, false));
        // Jamais au-dela de la limite.
        assertThat(StressRamp.growth(100, 198, 50, 0, 200)).isEqualTo(new StressRamp.Growth(2, true));
    }

    @Test
    void littleRequired_isTheRateTimesTheMeanResponseTime_withHeadroom() {
        assertThat(StressRamp.littleRequired(500, 200L)).isEqualTo(120);
        assertThat(StressRamp.littleRequired(2, 200L)).isEqualTo(1);
        assertThat(StressRamp.littleRequired(500, null)).as("aucune reponse mesuree").isZero();
    }

    @Test
    void aFastRampOnASmallPool_isMarkedBehind_forTheThroughputCriterion() {
        Harness h = new Harness(new StressRamp.Limits(1000, 5000, 3600, 50), 1, true);
        long rampStart = StressSettings.WARMUP_SECONDS + StressSettings.BASELINE_SECONDS;
        for (long s = 0; s < rampStart; s++) h.tick(s);
        assertThat(h.ramp.poolBehind(0, rampStart)).as("baseline a 2 req/s, aucune reponse lente").isFalse();
        // Reponses de 200 ms : a +50 req/s chaque seconde, le besoin depasse la croissance permise.
        for (int i = 0; i < 6; i++) h.live.record(-1, new StepOutcome(null, 200, 200, true, null, java.time.Instant.now()));
        h.tick(rampStart);
        assertThat(h.ramp.poolBehind(rampStart, rampStart + 1)).isTrue();
        assertThat(h.ramp.poolBehind(rampStart + 1, rampStart + 5)).isFalse();
    }

    @Test
    void reachingTheTargetRateLimit_holdsItThenEndsTheRamp() {
        // Reservoir fixe, loin de sa limite : seule la limite de debit peut arreter la montee.
        Harness h = new Harness(new StressRamp.Limits(1000, 45, 3600), 1, false);
        long rampStart = StressSettings.WARMUP_SECONDS + StressSettings.BASELINE_SECONDS;
        long reached = rampStart + (45 - StressSettings.BASELINE_RPS) - 1;

        for (long s = 0; s < reached; s++) {
            h.tick(s);
            assertThat(h.ramp.rising()).as("seconde %d", s).isTrue();
        }
        h.tick(reached);
        assertThat(h.ramp.offeredRps()).isEqualTo(45.0);
        assertThat(h.ramp.rising()).isFalse();
        for (long s = reached + 1; s < reached + StressSettings.HOLD_AT_LIMIT_SECONDS; s++) {
            h.tick(s);
            assertThat(h.ramp.limitReached()).as("seconde %d", s).isNull();
        }
        h.tick(reached + StressSettings.HOLD_AT_LIMIT_SECONDS);

        assertThat(h.ramp.limitReached()).isEqualTo(StressRamp.Limit.TARGET_RPS);
        assertThat(h.ramp.peakOfferedRps()).isEqualTo(45.0);
        assertThat(h.live.loadLevelAt(rampStart + 9).offeredRps()).isEqualTo(StressSettings.BASELINE_RPS + 10.0);
    }

    @Test
    void missedSlots_growThePool_andAPoolStuckAtItsLimit_endsTheRampWithAFrozenRate() {
        Harness h = new Harness(new StressRamp.Limits(2, 500, 3600), 1, true);

        h.tick(0);
        assertThat(h.pool.size).isEqualTo(1);
        // Seconde 1 : 2 creneaux manques (porte a 2 req/s, aucun VU n'est venu) -> +1 VU, limite de 2 atteinte.
        h.tick(1);
        assertThat(h.pool.size).isEqualTo(2);
        assertThat(h.ramp.poolAtLimit()).isTrue();
        assertThat(h.ramp.rising()).isFalse();
        double frozen = h.ramp.offeredRps();

        for (long s = 2; s <= 1 + StressSettings.HOLD_AT_LIMIT_SECONDS; s++) h.tick(s);

        assertThat(h.ramp.limitReached()).isEqualTo(StressRamp.Limit.VIRTUAL_USERS);
        assertThat(h.ramp.offeredRps()).isEqualTo(frozen);
        assertThat(h.ramp.peakVirtualUsers()).isEqualTo(2);
        assertThat(h.pool.size).isEqualTo(2);
    }

    @Test
    void maximumDuration_endsTheRamp() {
        Harness h = new Harness(new StressRamp.Limits(200, 500, 5), 1, true);
        h.tick(4);
        assertThat(h.ramp.limitReached()).isNull();
        h.tick(5);
        assertThat(h.ramp.limitReached()).isEqualTo(StressRamp.Limit.DURATION);
    }
}
