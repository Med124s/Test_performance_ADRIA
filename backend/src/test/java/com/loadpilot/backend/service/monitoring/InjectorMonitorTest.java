package com.loadpilot.backend.service.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** LoadPilot lui-meme pendant un test : lecture du processus, CPU maximal, saturation et avertissement. */
class InjectorMonitorTest {

    private static InjectorTrack.Sample cpu(Double percent) {
        return new InjectorTrack.Sample(percent, 50.0, 100, 4096, 40);
    }

    @Test
    void sample_readsTheLoadPilotProcess() {
        InjectorTrack.Sample s = InjectorMonitor.sample();
        assertThat(s.heapMaxMb()).isPositive();
        assertThat(s.heapUsedMb()).isBetween(0L, s.heapMaxMb());
        assertThat(s.platformThreads()).isPositive();
        if (s.processCpuPercent() != null) assertThat(s.processCpuPercent()).isBetween(0.0, 100.0);
        assertThat(InjectorMonitor.percent(0.853)).isEqualTo(85.3);
        assertThat(InjectorMonitor.percent(-1)).as("indisponible").isNull();
    }

    @Test
    void track_underTheThreshold_noWarning() {
        InjectorTrack track = new InjectorTrack(85);
        track.add(cpu(40.0));
        track.add(cpu(84.9));
        track.add(cpu(null)); // lecture indisponible : ignoree pour le CPU
        assertThat(track.overloaded()).isFalse();
        assertThat(track.warning()).isNull();
        assertThat(track.cpuMaxPercent()).isEqualTo(84.9);
        assertThat(track.samples()).isEqualTo(3);
        assertThat(track.latest().processCpuPercent()).isNull();
    }

    @Test
    void track_atOrAboveTheThreshold_overloadedWithAClearWarning_evenIfCpuComesBackDown() {
        InjectorTrack track = new InjectorTrack(85);
        track.add(cpu(30.0));
        track.add(cpu(92.4));
        track.add(cpu(20.0));
        assertThat(track.overloaded()).as("signale pour tout le test").isTrue();
        assertThat(track.cpuMaxPercent()).isEqualTo(92.4);
        assertThat(track.warning()).isEqualTo("Injecteur saturé : le CPU de LoadPilot a atteint 92 % (seuil 85 %). "
                + "Les temps de réponse mesurés peuvent être faussés : réduisez la charge ou utilisez une machine plus puissante.");
    }

    @Test
    void session_samplesImmediatelyThenStops() throws Exception {
        InjectorMonitor monitor = new InjectorMonitor(85);
        InjectorTrack track = monitor.newTrack();
        InjectorMonitor.Session session = monitor.start(track, 1);
        long until = System.currentTimeMillis() + 3000;
        while (track.samples() < 2 && System.currentTimeMillis() < until) Thread.sleep(50);
        session.stop();
        int samples = track.samples();
        assertThat(samples).isGreaterThanOrEqualTo(2);
        Thread.sleep(1300);
        assertThat(track.samples()).as("plus aucune lecture apres stop").isEqualTo(samples);
        assertThat(track.cpuWarningPercent()).isEqualTo(85.0);
    }
}
