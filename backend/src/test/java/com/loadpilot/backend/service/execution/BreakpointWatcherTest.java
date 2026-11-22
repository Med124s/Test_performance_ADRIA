package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.loadpilot.backend.service.monitoring.InjectorTrack;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Surveillance du mode stress, seconde par seconde avec une horloge simulee. */
class BreakpointWatcherTest {

    private static final long START = 1_000_000_000L;

    private final AtomicLong clock = new AtomicLong(START);
    private final LiveStats live = new LiveStats(List.of(), clock::get);
    private final RunningExecutionHandle handle = new RunningExecutionHandle();
    private final BreakpointWatcher watcher = new BreakpointWatcher(handle);

    BreakpointWatcherTest() {
        StressRamp ramp = new StressRamp(new StressRamp.Limits(200, 500, 3600), PacingGate.adjustable(2, clock::get), live,
                new StressRamp.Pool() {
                    @Override
                    public int virtualUsers() {
                        return 1;
                    }

                    @Override
                    public void add(int count) {
                    }
                });
        handle.liveStats(live);
        handle.stressRamp(ramp);
    }

    private void at(long second) {
        clock.set(START + TimeUnit.SECONDS.toNanos(second));
    }

    /** "perSecond" reponses par seconde en "ms", de "from" a "to" exclu, au debit offert note "offered". */
    private void traffic(long from, long to, int perSecond, long ms, double offered) {
        for (long s = from; s < to; s++) {
            at(s);
            live.recordLoadLevel(offered);
            for (int i = 0; i < perSecond; i++) live.record(-1, new StepOutcome(null, 200, ms, true, null, Instant.now()));
        }
    }

    private InjectorTrack saturatedFrom(long second) {
        InjectorTrack track = new InjectorTrack(85);
        for (int i = 0; i < StressSettings.INJECTOR_SATURATED_SAMPLES; i++) {
            track.add(new InjectorTrack.Sample(95.0, 60.0, 100, 1000, 20), START + TimeUnit.SECONDS.toNanos(second + 2L * i));
        }
        return track;
    }

    @Test
    void baselineWithTooFewResponses_isUnusable() {
        traffic(10, 20, 1, 100, 2);
        at(40);
        assertThat(watcher.step()).isInstanceOfSatisfying(BreakpointWatcher.BaselineUnusable.class,
                u -> assertThat(u.reason()).startsWith("Baseline impossible : 10 réponse(s) en 30 s"));
    }

    @Test
    void responseTimeExplosionAfterTheBaseline_isARupture_reportedAtItsOnset() {
        traffic(10, 40, 2, 100, 2);
        traffic(40, 55, 5, 600, 5);
        at(54);
        assertThat(watcher.step()).as("3e fenetre pas encore terminee").isNull();
        at(55);
        assertThat(watcher.step()).isInstanceOfSatisfying(BreakpointWatcher.Ruptured.class, r -> {
            assertThat(r.breakpoint().criteria()).containsExactly(BreakpointDetector.Criterion.RESPONSE_TIME);
            assertThat(r.breakpoint().onsetSecond()).isEqualTo(40);
            assertThat(r.breakpoint().offeredRps()).isEqualTo(5.0);
        });
    }

    @Test
    void loadPilotSaturatedBeforeAnyTargetAnomaly_isInconclusive() {
        traffic(10, 50, 5, 100, 5);
        handle.injector(saturatedFrom(44));
        at(50);
        assertThat(watcher.step()).isInstanceOfSatisfying(BreakpointWatcher.Inconclusive.class,
                i -> assertThat(i.reason()).startsWith(BreakpointWatcher.INCONCLUSIVE_PREFIX + " : CPU de LoadPilot à 95 % (seuil 85 %)"));
    }

    @Test
    void targetAnomalyStartedBeforeTheSaturation_waitsForItsConfirmation() {
        traffic(10, 40, 2, 100, 2);
        traffic(40, 50, 5, 600, 5);
        handle.injector(saturatedFrom(46));
        at(50);
        assertThat(watcher.step()).as("anomalie [40, 45[ anterieure a la saturation (46 s)").isNull();

        traffic(50, 55, 5, 600, 5);
        at(55);
        assertThat(watcher.step()).isInstanceOf(BreakpointWatcher.Ruptured.class);
    }
}
