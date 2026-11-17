package com.loadpilot.backend.service.execution;

import com.loadpilot.backend.service.http.NetworkErrors;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/** Compteurs en memoire d'une execution en cours, pour le suivi en direct, le frein automatique et le mode stress. */
public final class LiveStats {

    /** Fenetre glissante du debit en temps reel (secondes). */
    private static final int WINDOW_SECONDS = 5;
    /** Plus longue fenetre glissante disponible (secondes). */
    public static final int MAX_WINDOW_SECONDS = 300;
    private static final int SLOTS = MAX_WINDOW_SECONDS + 1;

    private final LongSupplier nanoClock;
    private final long startedNanos;

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
    // Compteurs par seconde (anneau), proteges par bucketSecond.
    private final long[] bucketSecond = new long[SLOTS];
    private final long[] bucketCount = new long[SLOTS];
    private final long[] bucketFailures = new long[SLOTS];
    private final long[] bucketThrottled = new long[SLOTS];
    /** Reponses HTTP recues (tous codes) et somme de leurs temps. */
    private final long[] bucketResponses = new long[SLOTS];
    private final long[] bucketResponseTime = new long[SLOTS];
    /** Histogramme des temps des reponses recues, alloue a la premiere reponse. */
    private final int[][] bucketLatency = new int[SLOTS][];
    private final long[] bucketTimeouts = new long[SLOTS];
    private final long[] bucketRefused = new long[SLOTS];
    private final long[] bucketTooManyRequests = new long[SLOTS];
    private final long[] bucketUnavailable = new long[SLOTS];
    // Niveau de charge par seconde (anneau, mode stress), protege par bucketSecond ; -1 = jamais note.
    private final long[] loadSecond = new long[SLOTS];
    private final int[] loadVirtualUsers = new int[SLOTS];
    private final double[] loadOfferedRps = new double[SLOTS];

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
        this(stepSpecs, System::nanoTime);
    }

    /** "nanoClock" : horloge des fenetres par seconde (tests). */
    LiveStats(List<StepExecutionSpec> stepSpecs, LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
        this.startedNanos = nanoClock.getAsLong();
        Arrays.fill(loadSecond, -1);
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

    /** Secondes entieres ecoulees depuis le demarrage des compteurs (seconde en cours). */
    public long elapsedSeconds() {
        return TimeUnit.NANOSECONDS.toSeconds(nanoClock.getAsLong() - startedNanos);
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
        Integer status = outcome.httpStatus();
        long second = elapsedSeconds();
        synchronized (bucketSecond) {
            int slot = (int) (second % SLOTS);
            if (bucketSecond[slot] != second) {
                bucketSecond[slot] = second;
                bucketCount[slot] = 0;
                bucketFailures[slot] = 0;
                bucketThrottled[slot] = 0;
                bucketResponses[slot] = 0;
                bucketResponseTime[slot] = 0;
                if (bucketLatency[slot] != null) Arrays.fill(bucketLatency[slot], 0);
                bucketTimeouts[slot] = 0;
                bucketRefused[slot] = 0;
                bucketTooManyRequests[slot] = 0;
                bucketUnavailable[slot] = 0;
            }
            bucketCount[slot]++;
            if (throttled) {
                bucketThrottled[slot]++;
            } else if (!outcome.success()) {
                bucketFailures[slot]++;
            }
            if (status != null) {
                bucketResponses[slot]++;
                bucketResponseTime[slot] += outcome.responseTimeMs();
                if (bucketLatency[slot] == null) bucketLatency[slot] = new int[LatencyHistogram.BUCKETS];
                bucketLatency[slot][LatencyHistogram.bucketOf(outcome.responseTimeMs())]++;
                if (status == ThrottlePolicy.TOO_MANY_REQUESTS) bucketTooManyRequests[slot]++;
                if (status == 502 || status == 503 || status == 504) bucketUnavailable[slot]++;
            } else if (NetworkErrors.TIMEOUT.equals(outcome.networkError())) {
                bucketTimeouts[slot]++;
            } else if (NetworkErrors.CONNECTION_REFUSED.equals(outcome.networkError())) {
                bucketRefused[slot]++;
            }
        }
    }

    /**
     * Compteurs d'une fenetre ; "requests" et "failures" excluent les 429 respectes ("throttled").
     * responses / responseTimeTotalMs / p95Ms : reponses HTTP recues (tous codes) ; timeouts et connectionsRefused :
     * echecs reseau sans reponse ; tooManyRequests : tous les 429 ; unavailable : 502, 503 et 504.
     */
    public record Window(long requests, long failures, long throttled, long responses, long responseTimeTotalMs, Long p95Ms,
            long timeouts, long connectionsRefused, long tooManyRequests, long unavailable, int seconds) {

        public Window(long requests, long failures, long throttled) {
            this(requests, failures, throttled, 0, 0, null, 0, 0, 0, 0, 0);
        }

        public Window(long requests, long failures) {
            this(requests, failures, 0);
        }

        /** Temps moyen des reponses recues, null sans reponse. */
        public Long avgResponseTimeMs() {
            return responses == 0 ? null : responseTimeTotalMs / responses;
        }

        /** Requetes terminees par seconde, 429 respectes compris. */
        public double throughputRps() {
            return seconds == 0 ? 0.0 : (requests + throttled) / (double) seconds;
        }
    }

    /** Fenetre glissante des dernieres secondes (seconde en cours comprise), bornee a [1, MAX_WINDOW_SECONDS]. */
    public Window recentWindow(int seconds) {
        int span = Math.max(1, Math.min(MAX_WINDOW_SECONDS, seconds));
        long nowSecond = elapsedSeconds();
        return window(nowSecond - span + 1, nowSecond + 1);
    }

    /** Fenetre des secondes [fromSecond, toSecond[ depuis le demarrage ; seules les MAX_WINDOW_SECONDS dernieres sont gardees. */
    public Window window(long fromSecond, long toSecond) {
        long n = 0;
        long f = 0;
        long t = 0;
        long responses = 0;
        long responseTime = 0;
        long timeouts = 0;
        long refused = 0;
        long tooMany = 0;
        long unavailable = 0;
        long[] latency = new long[LatencyHistogram.BUCKETS];
        synchronized (bucketSecond) {
            for (int i = 0; i < SLOTS; i++) {
                if (bucketCount[i] > 0 && bucketSecond[i] >= fromSecond && bucketSecond[i] < toSecond) {
                    n += bucketCount[i];
                    f += bucketFailures[i];
                    t += bucketThrottled[i];
                    responses += bucketResponses[i];
                    responseTime += bucketResponseTime[i];
                    timeouts += bucketTimeouts[i];
                    refused += bucketRefused[i];
                    tooMany += bucketTooManyRequests[i];
                    unavailable += bucketUnavailable[i];
                    if (bucketLatency[i] != null) {
                        for (int b = 0; b < latency.length; b++) latency[b] += bucketLatency[i][b];
                    }
                }
            }
        }
        int seconds = (int) Math.max(0, toSecond - fromSecond);
        return new Window(n - t, f, t, responses, responseTime, LatencyHistogram.percentile(latency, 95), timeouts, refused,
                tooMany, unavailable, seconds);
    }

    /** Niveau de charge d'une seconde : utilisateurs virtuels actifs et debit offert (req/s). */
    public record LoadLevel(long second, int activeVirtualUsers, double offeredRps) {
    }

    /** Note le niveau de charge de la seconde en cours (mode stress) ; un nouvel appel dans la meme seconde le remplace. */
    public void recordLoadLevel(double offeredRps) {
        long second = elapsedSeconds();
        synchronized (bucketSecond) {
            int slot = (int) (second % SLOTS);
            loadSecond[slot] = second;
            loadVirtualUsers[slot] = Math.max(0, activeVirtualUsers.get());
            loadOfferedRps[slot] = offeredRps;
        }
    }

    /** Niveau de charge note pour cette seconde, null s'il n'a pas ete note ou n'est plus en memoire. */
    public LoadLevel loadLevelAt(long second) {
        if (second < 0) return null;
        synchronized (bucketSecond) {
            int slot = (int) (second % SLOTS);
            return loadSecond[slot] == second ? new LoadLevel(second, loadVirtualUsers[slot], loadOfferedRps[slot]) : null;
        }
    }

    public Snapshot snapshot() {
        long elapsedNanos = nanoClock.getAsLong() - startedNanos;
        long nowSecond = TimeUnit.NANOSECONDS.toSeconds(elapsedNanos);
        long recent = 0;
        synchronized (bucketSecond) {
            for (int i = 0; i < SLOTS; i++) {
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
