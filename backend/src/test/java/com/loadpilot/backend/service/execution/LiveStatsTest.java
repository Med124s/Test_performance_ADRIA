package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.loadpilot.backend.enums.HttpMethod;
import com.loadpilot.backend.service.http.NetworkErrors;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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

    /** Horloge simulee : seconde courante reglable. */
    private static final class FakeClock {
        long nanos = 1_000_000_000L;

        void second(long s) {
            nanos = 1_000_000_000L + TimeUnit.SECONDS.toNanos(s) + 1;
        }
    }

    private static StepOutcome response(int status, long ms) {
        return new StepOutcome(null, status, ms, status < 400, null, Instant.now());
    }

    private static StepOutcome networkFailure(String kind, long ms) {
        return new StepOutcome(null, null, ms, false, "echec", Instant.now(), null, null, null, null, kind);
    }

    @Test
    void window_countsResponseTimes_networkFailures_429_andUnavailable_perSecond() {
        FakeClock clock = new FakeClock();
        LiveStats stats = new LiveStats(List.of(spec("a")), () -> clock.nanos);
        clock.second(0);
        stats.record(0, response(200, 100));
        stats.record(0, response(200, 300));
        clock.second(1);
        stats.record(0, response(503, 50));
        stats.record(0, response(429, 10), true);
        stats.record(0, networkFailure(NetworkErrors.TIMEOUT, 10_000));
        stats.record(0, networkFailure(NetworkErrors.CONNECTION_REFUSED, 2));
        stats.record(0, networkFailure("DNS", 1));
        clock.second(2);
        stats.record(0, response(200, 999));

        LiveStats.Window w = stats.window(0, 2);
        assertThat(w.requests()).isEqualTo(6);
        assertThat(w.throttled()).isEqualTo(1);
        assertThat(w.failures()).isEqualTo(4);
        assertThat(w.responses()).isEqualTo(4);
        assertThat(w.avgResponseTimeMs()).isEqualTo((100 + 300 + 50 + 10) / 4);
        assertThat(w.p95Ms()).isBetween(300L, 345L);
        assertThat(w.timeouts()).isEqualTo(1);
        assertThat(w.connectionsRefused()).isEqualTo(1);
        assertThat(w.tooManyRequests()).isEqualTo(1);
        assertThat(w.unavailable()).isEqualTo(1);
        assertThat(w.seconds()).isEqualTo(2);
        assertThat(w.throughputRps()).isEqualTo(3.5);

        LiveStats.Window last = stats.window(2, 3);
        assertThat(last.requests()).isEqualTo(1);
        assertThat(last.p95Ms()).isBetween(999L, 1_149L);
    }

    @Test
    void window_withoutResponse_hasNoAverageNorP95() {
        FakeClock clock = new FakeClock();
        LiveStats stats = new LiveStats(List.of(spec("a")), () -> clock.nanos);
        stats.record(0, networkFailure(NetworkErrors.TIMEOUT, 10_000));

        LiveStats.Window w = stats.window(0, 1);
        assertThat(w.timeouts()).isEqualTo(1);
        assertThat(w.avgResponseTimeMs()).isNull();
        assertThat(w.p95Ms()).isNull();
    }

    @Test
    void recentWindow_keepsItsMeaning_currentSecondIncluded_throttledExcluded() {
        FakeClock clock = new FakeClock();
        LiveStats stats = new LiveStats(List.of(spec("a")), () -> clock.nanos);
        clock.second(10);
        stats.record(0, response(500, 5));
        clock.second(14);
        stats.record(0, response(200, 5));
        stats.record(0, response(429, 5), true);
        clock.second(15);
        stats.record(0, response(200, 5));

        assertThat(stats.recentWindow(5)).satisfies(w -> {
            assertThat(w.requests()).isEqualTo(2);
            assertThat(w.failures()).isZero();
            assertThat(w.throttled()).isEqualTo(1);
        });
        assertThat(stats.recentWindow(6).failures()).isEqualTo(1);
    }

    @Test
    void secondsOlderThanTheRing_areForgotten() {
        FakeClock clock = new FakeClock();
        LiveStats stats = new LiveStats(List.of(spec("a")), () -> clock.nanos);
        stats.record(0, response(500, 5));
        clock.second(LiveStats.MAX_WINDOW_SECONDS + 1);
        stats.record(0, response(200, 5));

        assertThat(stats.window(0, 1).requests()).isZero();
        assertThat(stats.window(0, LiveStats.MAX_WINDOW_SECONDS + 2).requests()).isEqualTo(1);
    }

    @Test
    void loadLevel_isKeptPerSecond_withActiveVirtualUsers() {
        FakeClock clock = new FakeClock();
        LiveStats stats = new LiveStats(List.of(spec("a")), () -> clock.nanos);
        stats.virtualUserStarted();
        stats.virtualUserStarted();
        clock.second(3);
        stats.recordLoadLevel(12.0);
        stats.recordLoadLevel(13.0);
        clock.second(4);
        stats.virtualUserStarted();
        stats.recordLoadLevel(14.0);

        assertThat(stats.elapsedSeconds()).isEqualTo(4);
        assertThat(stats.loadLevelAt(3)).isEqualTo(new LiveStats.LoadLevel(3, 2, 13.0));
        assertThat(stats.loadLevelAt(4)).isEqualTo(new LiveStats.LoadLevel(4, 3, 14.0));
        assertThat(stats.loadLevelAt(2)).isNull();
        assertThat(stats.loadLevelAt(-1)).isNull();

        clock.second(4 + LiveStats.MAX_WINDOW_SECONDS + 1);
        stats.recordLoadLevel(99.0);
        assertThat(stats.loadLevelAt(4)).isNull();
    }

    @Test
    void emptyStats_haveNoAverage() {
        LiveStats.Snapshot s = new LiveStats(List.of(spec("a"))).snapshot();
        assertThat(s.requests()).isZero();
        assertThat(s.avgResponseTimeMs()).isNull();
        assertThat(s.steps().get(0).avgResponseTimeMs()).isNull();
    }
}
