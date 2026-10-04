package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.loadpilot.backend.enums.HttpMethod;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LiveStatsTest {

    private static StepExecutionSpec spec(String name) {
        return new StepExecutionSpec(UUID.randomUUID(), name, HttpMethod.GET, "/x", null, null, null, null, null, null,
                null, null, null);
    }

    private static StepOutcome outcome(boolean success, long ms) {
        return new StepOutcome(null, success ? 200 : 500, ms, success, null, Instant.now());
    }

    @Test
    void countsRequestsPerStep_activeUsers_iterations_andAverages() {
        LiveStats stats = new LiveStats(List.of(spec("login"), spec("balance")));
        stats.virtualUserStarted();
        stats.virtualUserStarted();
        stats.record(0, outcome(true, 100));
        stats.record(1, outcome(true, 300));
        stats.record(1, outcome(false, 200));
        stats.iterationCompleted();
        stats.virtualUserFinished();

        LiveStats.Snapshot s = stats.snapshot();
        assertThat(s.activeVirtualUsers()).isEqualTo(1);
        assertThat(s.startedVirtualUsers()).isEqualTo(2);
        assertThat(s.completedIterations()).isEqualTo(1);
        assertThat(s.requests()).isEqualTo(3);
        assertThat(s.successes()).isEqualTo(2);
        assertThat(s.failures()).isEqualTo(1);
        assertThat(s.avgResponseTimeMs()).isEqualTo(200);
        assertThat(s.throughputRps()).isEqualTo(3.0);
        assertThat(s.steps()).extracting(LiveStats.StepSnapshot::name).containsExactly("login", "balance");
        assertThat(s.steps().get(1)).satisfies(step -> {
            assertThat(step.requests()).isEqualTo(2);
            assertThat(step.failures()).isEqualTo(1);
            assertThat(step.avgResponseTimeMs()).isEqualTo(250);
        });
    }

    @Test
    void emptyStats_haveNoAverage() {
        LiveStats.Snapshot s = new LiveStats(List.of(spec("a"))).snapshot();
        assertThat(s.requests()).isZero();
        assertThat(s.avgResponseTimeMs()).isNull();
        assertThat(s.steps().get(0).avgResponseTimeMs()).isNull();
    }
}
