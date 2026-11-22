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
        assertThat(StressRamp.offeredRpsAt(0, 500)).isEqualTo(StressSettings.BASELINE_RPS);
        assertThat(StressRamp.offeredRpsAt(rampStart - 1, 500)).isEqualTo(StressSettings.BASELINE_RPS);
        assertThat(StressRamp.offeredRpsAt(rampStart, 500)).isEqualTo(StressSettings.BASELINE_RPS + 1.0);
        assertThat(StressRamp.offeredRpsAt(rampStart + 9, 500)).isEqualTo(StressSettings.BASELINE_RPS + 10.0);
        assertThat(StressRamp.offeredRpsAt(rampStart + 10_000, 500)).isEqualTo(500.0);
    }

    @Test
    void virtualUsersToAdd_followsTheDeficit_onlyWhenAllAreBusy() {
        assertThat(StressRamp.virtualUsersToAdd(0, 0, 10, 20, 200)).as("aucun creneau manque").isZero();
        assertThat(StressRamp.virtualUsersToAdd(10, 1, 10, 20, 200)).as("un VU attend son creneau").isZero();
        assertThat(StressRamp.virtualUsersToAdd(10, 0, 200, 20, 200)).as("deja a la limite").isZero();
        // 10 VU font 20 req/s (2 chacun) : 4 creneaux manques par seconde -> 2 VU de plus.
        assertThat(StressRamp.virtualUsersToAdd(4, 0, 10, 20, 200)).isEqualTo(2);
        // Deficit de 100 req/s : 50 VU necessaires, croissance bornee a +50 % (5).
        assertThat(StressRamp.virtualUsersToAdd(100, 0, 10, 20, 200)).isEqualTo(5);
        // Aucun debit mesure encore : au plus +50 %, au moins 1.
        assertThat(StressRamp.virtualUsersToAdd(3, 0, 1, 0, 200)).isEqualTo(1);
        // Jamais au-dela de la limite.
        assertThat(StressRamp.virtualUsersToAdd(100, 0, 198, 50, 200)).isEqualTo(2);
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
