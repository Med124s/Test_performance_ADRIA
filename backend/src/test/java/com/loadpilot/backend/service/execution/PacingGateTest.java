package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PacingGateTest {

    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);

    @Test
    void adjustableGate_countsSlotsNobodyTook_withoutCatchingThemUp() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        PacingGate gate = PacingGate.adjustable(10, clock::get);

        assertThat(gate.acquire()).isTrue();
        clock.addAndGet(1_000 * MS);

        // Creneaux de 100 ms : 9 sont passes sans preneur depuis le creneau suivant (t + 100 ms).
        assertThat(gate.drainMissedSlots()).isEqualTo(9);
        assertThat(gate.drainMissedSlots()).isZero();

        // Pas de rafale de rattrapage : un seul creneau immediat, le suivant 100 ms plus tard.
        long start = System.nanoTime();
        assertThat(gate.acquire()).isTrue();
        assertThat(gate.acquire()).isTrue();
        assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(80 * MS);
        assertThat(gate.drainMissedSlots()).isZero();
    }

    @Test
    void adjustableGate_rateChange_appliesToTheNextSlots() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        PacingGate gate = PacingGate.adjustable(2, clock::get);
        assertThat(gate.rate()).isEqualTo(2.0);

        gate.setRate(20);
        assertThat(gate.rate()).isEqualTo(20.0);
        assertThat(gate.acquire()).isTrue();
        clock.addAndGet(1_000 * MS);
        // Creneaux de 50 ms : 19 passes sans preneur apres le premier.
        assertThat(gate.drainMissedSlots()).isEqualTo(19);
    }

    @Test
    void fixedGate_isNotAdjustable() {
        PacingGate gate = new PacingGate(10);
        assertThatThrownBy(() -> gate.setRate(20)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void waiting_countsVirtualUsersBlockedOnTheirSlot() throws Exception {
        PacingGate gate = PacingGate.adjustable(2);
        assertThat(gate.acquire()).isTrue();
        Thread second = Thread.ofVirtual().start(gate::acquire);
        long deadline = System.nanoTime() + 300 * MS;
        while (gate.waiting() == 0 && System.nanoTime() < deadline) Thread.onSpinWait();

        assertThat(gate.waiting()).isEqualTo(1);
        second.join();
        assertThat(gate.waiting()).isZero();
    }
}
