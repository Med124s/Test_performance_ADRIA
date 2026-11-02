package com.loadpilot.backend.service.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/** Compteurs en memoire d'une execution en cours, pour le suivi en direct et le frein automatique. */
public final class LiveStats {

    /** Fenetre glissante du debit en temps reel (secondes). */
    private static final int WINDOW_SECONDS = 5;
    /** Plus longue fenetre glissante disponible (secondes). */
    public static final int MAX_WINDOW_SECONDS = 300;

    private final long startedNanos = System.nanoTime();

    /** System.nanoTime() du demarrage des compteurs. */
    public long startedNanos() {
        return startedNanos;
    }

    private final AtomicInteger activeVirtualUsers = new AtomicInteger();
    private final AtomicInteger startedVirtualUsers = new AtomicInteger();
    private final LongAdder completedIterations = new LongAdder();
    private final LongAdder requests = new LongAdder();
    private final LongAdder successes = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final LongAdder responseTimeTotal = new LongAdder();
    private final List<StepCounters> steps = new ArrayList<>();
    // Compteurs par seconde (anneau) : requetes et echecs.
    private final long[] bucketSecond = new long[MAX_WINDOW_SECONDS + 1];
    private final long[] bucketCount = new long[MAX_WINDOW_SECONDS + 1];
    private final long[] bucketFailures = new long[MAX_WINDOW_SECONDS + 1];
    private final long[] bucketThrottled = new long[MAX_WINDOW_SECONDS + 1];

    private static final class StepCounters {
        final StepExecutionSpec spec;
        final LongAdder requests = new LongAdder();
        final LongAdder successes = new LongAdder();
        final LongAdder failures = new LongAdder();
        final LongAdder responseTimeTotal = new LongAdder();

        StepCounters(StepExecutionSpec spec) {
            this.spec = spec;
        }
    }

    public record StepSnapshot(String stepId, String name, String method, long requests, long successes, long failures,
            Long avgResponseTimeMs) {
    }

    public record Snapshot(long elapsedMs, int activeVirtualUsers, int startedVirtualUsers, long completedIterations,
            long requests, long successes, long failures, double throughputRps, Long avgResponseTimeMs,
            List<StepSnapshot> steps) {
    }

    public LiveStats(List<StepExecutionSpec> stepSpecs) {
        stepSpecs.forEach(s -> steps.add(new StepCounters(s)));
    }

    public void virtualUserStarted() {
        startedVirtualUsers.incrementAndGet();
        activeVirtualUsers.incrementAndGet();
    }

    public void virtualUserFinished() {
        activeVirtualUsers.decrementAndGet();
    }

    public void iterationCompleted() {
        completedIterations.increment();
    }

    /** Requetes enregistrees depuis le debut. */
    public long totalRequests() {
        return requests.sum();
    }

    /** stepIndex : position (0..n-1) de l'etape dans le scenario. */
    public void record(int stepIndex, StepOutcome outcome) {
        record(stepIndex, outcome, false);
    }

    /** "throttled" : 429 respecte, compte comme un echec mais hors de la fenetre du frein automatique. */
    public void record(int stepIndex, StepOutcome outcome, boolean throttled) {
        requests.increment();
        (outcome.success() ? successes : failures).increment();
        responseTimeTotal.add(outcome.responseTimeMs());
        if (stepIndex >= 0 && stepIndex < steps.size()) {
            StepCounters step = steps.get(stepIndex);
            step.requests.increment();
            (outcome.success() ? step.successes : step.failures).increment();
            step.responseTimeTotal.add(outcome.responseTimeMs());
        }
        long second = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedNanos);
        synchronized (bucketSecond) {
            int slot = (int) (second % bucketSecond.length);
            if (bucketSecond[slot] != second) {
                bucketSecond[slot] = second;
                bucketCount[slot] = 0;
                bucketFailures[slot] = 0;
                bucketThrottled[slot] = 0;
            }
            bucketCount[slot]++;
            if (throttled) {
                bucketThrottled[slot]++;
            } else if (!outcome.success()) {
                bucketFailures[slot]++;
            }
        }
    }

    /** Requetes et echecs sur les {@code seconds} dernieres secondes ; "throttled" : 429 respectes, exclus des deux. */
    public record Window(long requests, long failures, long throttled) {
        public Window(long requests, long failures) {
            this(requests, failures, 0);
        }
    }

    /** Fenetre glissante des dernieres secondes, bornee a [1, MAX_WINDOW_SECONDS]. */
    public Window recentWindow(int seconds) {
        int span = Math.max(1, Math.min(MAX_WINDOW_SECONDS, seconds));
        long nowSecond = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedNanos);
        long n = 0;
        long f = 0;
        long t = 0;
        synchronized (bucketSecond) {
            for (int i = 0; i < bucketSecond.length; i++) {
                if (bucketCount[i] > 0 && bucketSecond[i] > nowSecond - span && bucketSecond[i] <= nowSecond) {
                    n += bucketCount[i];
                    f += bucketFailures[i];
                    t += bucketThrottled[i];
                }
            }
        }
        return new Window(n - t, f, t);
    }

    public Snapshot snapshot() {
        long elapsedNanos = System.nanoTime() - startedNanos;
        long nowSecond = TimeUnit.NANOSECONDS.toSeconds(elapsedNanos);
        long recent = 0;
        synchronized (bucketSecond) {
            for (int i = 0; i < bucketSecond.length; i++) {
                if (bucketCount[i] > 0 && bucketSecond[i] > nowSecond - WINDOW_SECONDS && bucketSecond[i] <= nowSecond) {
                    recent += bucketCount[i];
                }
            }
        }
        // Au debut, fenetre ramenee au temps ecoule.
        double windowSeconds = Math.min(WINDOW_SECONDS, Math.max(1.0, elapsedNanos / 1e9));
        long total = requests.sum();
        List<StepSnapshot> stepSnapshots = steps.stream().map(s -> {
            long n = s.requests.sum();
            return new StepSnapshot(s.spec.stepId() == null ? null : s.spec.stepId().toString(), s.spec.name(),
                    s.spec.method() == null ? null : s.spec.method().name(), n, s.successes.sum(), s.failures.sum(),
                    n == 0 ? null : s.responseTimeTotal.sum() / n);
        }).toList();
        return new Snapshot(TimeUnit.NANOSECONDS.toMillis(elapsedNanos), Math.max(0, activeVirtualUsers.get()),
                startedVirtualUsers.get(), completedIterations.sum(), total, successes.sum(), failures.sum(),
                Math.round(recent / windowSeconds * 10) / 10.0, total == 0 ? null : responseTimeTotal.sum() / total,
                stepSnapshots);
    }
}
