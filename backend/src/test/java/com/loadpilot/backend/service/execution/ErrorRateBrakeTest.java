package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Frein automatique sur taux d'erreurs : reglage, decision, fenetre glissante, surveillance, annulation concurrente. */
class ErrorRateBrakeTest {

    private static StepOutcome outcome(boolean success) {
        return new StepOutcome(null, success ? 200 : 500, 5, success, null, Instant.now());
    }

    @Test
    void config_disabledByDefault_windowDefaultedAndBounded() {
        assertThat(ErrorRateBrake.Config.of(null, 30)).isNull();
        assertThat(ErrorRateBrake.Config.of(0.0, 30)).as("0 = desactive").isNull();
        assertThat(ErrorRateBrake.Config.of(-5.0, null)).isNull();
        assertThat(ErrorRateBrake.Config.of(20.0, null)).isEqualTo(new ErrorRateBrake.Config(20.0, 30));
        assertThat(ErrorRateBrake.Config.of(20.0, 1).windowSeconds()).isEqualTo(5);
        assertThat(ErrorRateBrake.Config.of(20.0, 10_000).windowSeconds()).isEqualTo(LiveStats.MAX_WINDOW_SECONDS);
    }

    @Test
    void evaluate_tripsOnlyAboveThreshold_withEnoughRequests() {
        ErrorRateBrake.Config config = new ErrorRateBrake.Config(20.0, 30);
        assertThat(ErrorRateBrake.evaluate(null, new LiveStats.Window(100, 100))).as("desactive").isNull();
        assertThat(ErrorRateBrake.evaluate(config, new LiveStats.Window(19, 19))).as("trop peu de requetes").isNull();
        assertThat(ErrorRateBrake.evaluate(config, new LiveStats.Window(100, 20))).as("egal au seuil : toléré").isNull();
        assertThat(ErrorRateBrake.evaluate(config, new LiveStats.Window(100, 21)))
                .isEqualTo("Arrêt automatique : 21 % d'erreurs sur les 30 dernières secondes (21 sur 100 requêtes), "
                        + "au-delà du seuil de 20 %.")
                .startsWith(ErrorRateBrake.REASON_PREFIX);
        assertThat(ErrorRateBrake.evaluate(new ErrorRateBrake.Config(100.0, 30), new LiveStats.Window(50, 50))).isNull();
    }

    @Test
    void liveStatsWindow_countsRequestsAndFailuresOfTheLastSeconds() {
        LiveStats stats = new LiveStats(List.of());
        for (int i = 0; i < 30; i++) stats.record(-1, outcome(i % 3 != 0));
        assertThat(stats.recentWindow(30)).isEqualTo(new LiveStats.Window(30, 10));
        assertThat(stats.recentWindow(10_000)).as("borne a 300 s").isEqualTo(new LiveStats.Window(30, 10));
        // Le suivi en direct existant ne change pas.
        assertThat(stats.snapshot().failures()).isEqualTo(10);
    }

    @Test
    void start_tripsOnce_whenTheWindowExceedsTheThreshold() throws Exception {
        RunningExecutionHandle handle = new RunningExecutionHandle();
        LiveStats stats = handle.startLiveStats(List.of());
        for (int i = 0; i < 40; i++) stats.record(-1, outcome(false));
        List<String> trips = new CopyOnWriteArrayList<>();
        CountDownLatch tripped = new CountDownLatch(1);
        ErrorRateBrake.Session session = ErrorRateBrake.start(new ErrorRateBrake.Config(10.0, 30), handle, reason -> {
            trips.add(reason);
            handle.requestCancellation(reason);
            tripped.countDown();
        });
        assertThat(tripped.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        Thread.sleep(1200);
        session.stop();
        assertThat(trips).hasSize(1);
        assertThat(handle.isCancelled()).isTrue();
        assertThat(handle.cancellationReason()).startsWith(ErrorRateBrake.REASON_PREFIX);
        assertThat(handle.cancelledBy()).isNull();
    }

    @Test
    void start_neverTrips_whenDisabled_belowThreshold_orStopped() throws Exception {
        RunningExecutionHandle handle = new RunningExecutionHandle();
        LiveStats stats = handle.startLiveStats(List.of());
        for (int i = 0; i < 100; i++) stats.record(-1, outcome(i % 10 != 0)); // 10 % d'erreurs
        AtomicInteger trips = new AtomicInteger();
        ErrorRateBrake.Session disabled = ErrorRateBrake.start(null, handle, r -> trips.incrementAndGet());
        ErrorRateBrake.Session below = ErrorRateBrake.start(new ErrorRateBrake.Config(50.0, 30), handle, r -> trips.incrementAndGet());
        ErrorRateBrake.Session stopped = ErrorRateBrake.start(new ErrorRateBrake.Config(1.0, 30), handle, r -> trips.incrementAndGet());
        stopped.stop();
        Thread.sleep(1500);
        disabled.stop();
        below.stop();
        stopped.stop();
        assertThat(trips.get()).isZero();
        assertThat(handle.isCancelled()).isFalse();
    }

    @Test
    void concurrentCancellations_firstOneWins_reasonAndAuthorNeverMixed() throws Exception {
        for (int round = 0; round < 200; round++) {
            RunningExecutionHandle handle = new RunningExecutionHandle();
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger winners = new AtomicInteger();
            Thread manual = Thread.ofVirtual().start(() -> {
                await(go);
                if (handle.requestCancellationBy("alice")) winners.incrementAndGet();
            });
            Thread brake = Thread.ofVirtual().start(() -> {
                await(go);
                if (handle.requestCancellation(ErrorRateBrake.REASON_PREFIX + "test")) winners.incrementAndGet();
            });
            go.countDown();
            manual.join();
            brake.join();
            assertThat(winners.get()).isEqualTo(1);
            assertThat(handle.isCancelled()).isTrue();
            // Soit l'utilisateur (auteur, pas de motif), soit le frein (motif, pas d'auteur) : jamais les deux.
            assertThat(handle.cancelledBy() == null).isNotEqualTo(handle.cancellationReason() == null);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
