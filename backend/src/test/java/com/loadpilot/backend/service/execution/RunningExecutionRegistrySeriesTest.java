package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loadpilot.backend.exception.ExecutionLimitExceededException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Serie d'essais de stress : reservation gardee pendant la pause, reprise par l'essai suivant ou liberee. */
class RunningExecutionRegistrySeriesTest {

    @Test
    void reservationIsKeptDuringThePause_thenTakenByTheNextRun() {
        RunningExecutionRegistry registry = new RunningExecutionRegistry(500, 10);
        UUID series = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        registry.reserveCapacity(200, true);
        registry.registerReserved(first, 200, true);

        registry.unregisterKeepingReservation(first, series);
        assertThat(registry.activeExecutionCount()).isZero();
        assertThat(registry.reservedVirtualUsersSnapshot()).isEqualTo(200);
        assertThat(registry.holds(series)).isTrue();
        assertThatThrownBy(() -> registry.reserveCapacity(10, false)).isInstanceOf(ExecutionLimitExceededException.class)
                .hasMessage(RunningExecutionRegistry.STRESS_SERIES_PAUSE);

        UUID second = UUID.randomUUID();
        RunningExecutionHandle handle = registry.registerHeld(series, second);
        assertThat(handle).isNotNull();
        assertThat(handle.isStressMode()).isTrue();
        assertThat(registry.holds(series)).isFalse();
        assertThatThrownBy(() -> registry.reserveCapacity(10, false)).hasMessage(RunningExecutionRegistry.STRESS_RUNNING);

        registry.unregister(second);
        assertThat(registry.reservedVirtualUsersSnapshot()).isZero();
        registry.reserveCapacity(10, false);
    }

    @Test
    void releasingTheHeldReservation_freesTheServer_once() {
        RunningExecutionRegistry registry = new RunningExecutionRegistry(500, 10);
        UUID series = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        registry.reserveCapacity(200, true);
        registry.registerReserved(first, 200, true);
        registry.unregisterKeepingReservation(first, series);

        registry.releaseHeld(series);
        registry.releaseHeld(series);
        assertThat(registry.reservedVirtualUsersSnapshot()).isZero();
        assertThat(registry.registerHeld(series, UUID.randomUUID())).as("serie annulee pendant la pause").isNull();
        registry.reserveCapacity(200, true);
    }
}
