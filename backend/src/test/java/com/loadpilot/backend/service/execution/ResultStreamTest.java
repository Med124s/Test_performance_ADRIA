package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.loadpilot.backend.service.report.PercentileCalculator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Ecriture des resultats au fil du test : lots, delai, fermeture, erreurs de la base, totaux exacts. */
class ResultStreamTest {

    private static final UUID STEP = UUID.randomUUID();

    private static StepOutcome outcome(int i, boolean success) {
        return new StepOutcome(STEP, success ? 200 : 500, 10 + (i * 37L) % 500, success, success ? null : "Reponse HTTP 500 #" + i,
                Instant.now(), 0, i, "/x");
    }

    /** Lots recus par le « writer », dans l'ordre. */
    private static final class Recorder implements ResultStream.Writer {
        final List<List<StepOutcome>> batches = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void write(List<StepOutcome> batch) {
            batches.add(List.copyOf(batch));
        }

        List<StepOutcome> all() {
            synchronized (batches) {
                return batches.stream().flatMap(List::stream).toList();
            }
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(condition.getAsBoolean()).as("condition atteinte a temps").isTrue();
    }

    @Test
    void fullBatches_areWrittenDuringTheTest_notOnlyAtTheEnd() throws Exception {
        Recorder recorder = new Recorder();
        ResultStream stream = new ResultStream("t1", recorder, 50, 60_000);
        for (int i = 0; i < 120; i++) stream.accept(outcome(i, true));
        // Deux lots complets ecrits sans attendre la fin (delai d'une minute, jamais atteint ici).
        await(() -> recorder.all().size() == 100);
        assertThat(recorder.batches).allSatisfy(b -> assertThat(b).hasSize(50));
        assertThat(stream.inMemory()).isEqualTo(20);

        stream.close();
        assertThat(recorder.all()).hasSize(120);
        assertThat(recorder.all()).extracting(StepOutcome::iteration).containsExactlyElementsOf(
                java.util.stream.IntStream.range(0, 120).boxed().toList());
        assertThat(stream.inMemory()).isZero();
        assertThat(stream.written()).isEqualTo(120);
    }

    @Test
    void anIncompleteBatch_isWrittenAfterTheFlushInterval() throws Exception {
        Recorder recorder = new Recorder();
        ResultStream stream = new ResultStream("t2", recorder, 100, 100);
        for (int i = 0; i < 7; i++) stream.accept(outcome(i, true));
        await(() -> recorder.all().size() == 7);
        stream.close();
        assertThat(recorder.all()).hasSize(7);
    }

    @Test
    void aFailingDatabase_isRetried_andNothingIsLost() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Recorder recorder = new Recorder();
        ResultStream stream = new ResultStream("t3", batch -> {
            if (calls.incrementAndGet() <= 2) throw new IllegalStateException("base indisponible (simulée)");
            recorder.write(batch);
        }, 10, 50);
        for (int i = 0; i < 25; i++) stream.accept(outcome(i, true));
        stream.close();
        assertThat(recorder.all()).hasSize(25);
        assertThat(recorder.all()).extracting(StepOutcome::iteration).isSorted();
        assertThat(stream.lost()).isZero();
    }

    @Test
    void aDatabaseDownUntilTheEnd_givesUpAfterBoundedRetries_closeNeverHangs() {
        ResultStream stream = new ResultStream("t4", batch -> {
            throw new IllegalStateException("base arrêtée (simulée)");
        }, 10, 20);
        for (int i = 0; i < 15; i++) stream.accept(outcome(i, true));
        long start = System.nanoTime();
        stream.close();
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start)).isLessThan(10);
        assertThat(stream.lost()).isEqualTo(15);
        assertThat(stream.written()).isZero();
        // Totaux toujours complets : le statut final reste calcule sur toutes les requetes.
        assertThat(stream.summary().total()).isEqualTo(15);
    }

    @Test
    void accept_neverWaitsForTheDatabase_evenWhenWritesAreSlow() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Recorder recorder = new Recorder();
        ResultStream stream = new ResultStream("t5", batch -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            recorder.write(batch);
        }, 100, 50);
        ExecutorService vus = Executors.newVirtualThreadPerTaskExecutor();
        long start = System.nanoTime();
        for (int vu = 0; vu < 20; vu++) {
            int base = vu * 1000;
            vus.submit(() -> {
                for (int i = 0; i < 1000; i++) stream.accept(outcome(base + i, true));
            });
        }
        vus.shutdown();
        assertThat(vus.awaitTermination(5, TimeUnit.SECONDS)).as("20 000 resultats acceptes sans attendre la base").isTrue();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(elapsedMs).isLessThan(3000);
        assertThat(stream.inMemory()).as("tout est en attente d'ecriture").isEqualTo(20_000);
        release.countDown();
        stream.close();
        assertThat(recorder.all()).hasSize(20_000);
    }

    @Test
    void resultsAfterClose_areIgnored_closeIsIdempotent() {
        Recorder recorder = new Recorder();
        ResultStream stream = new ResultStream("t6", recorder, 10, 50);
        stream.accept(outcome(1, true));
        stream.close();
        stream.accept(outcome(2, true));
        stream.close();
        assertThat(recorder.all()).hasSize(1);
        assertThat(stream.summary().total()).isEqualTo(1);
    }

    @Test
    void summary_isExact_sameP95AsTheReport_firstErrors() {
        List<StepOutcome> outcomes = new ArrayList<>();
        for (int i = 0; i < 2_345; i++) outcomes.add(outcome(i, i % 9 != 4));
        ResultSummary summary = ResultSummary.of(outcomes);
        List<Long> times = outcomes.stream().map(StepOutcome::responseTimeMs).toList();
        for (double p : new double[] {50, 90, 95, 99, 100}) {
            assertThat(summary.percentile(p)).as("P" + p).isEqualTo(PercentileCalculator.percentile(times, p));
        }
        assertThat(summary.total()).isEqualTo(2_345);
        assertThat(summary.failures()).isEqualTo(outcomes.stream().filter(o -> !o.success()).count());
        assertThat(summary.firstFailureError()).isEqualTo("Reponse HTTP 500 #4");
        assertThat(summary.anyResponse()).isTrue();
        assertThat(ResultSummary.of(List.of()).percentile(95)).isNull();
    }

    @Test
    void verdict_isTheSame_fromTheSummary_asFromTheFullList() {
        List<StepOutcome> outcomes = new ArrayList<>();
        for (int i = 0; i < 500; i++) outcomes.add(outcome(i, i % 20 != 0));
        ScenarioExecutionOutcome full = new ScenarioExecutionOutcome(outcomes, com.loadpilot.backend.enums.ExecutionStatus.FAILED, "x");
        ScenarioExecutionOutcome streamed = new ScenarioExecutionOutcome(List.of(), com.loadpilot.backend.enums.ExecutionStatus.FAILED, "x",
                ResultSummary.of(outcomes));
        for (Long maxP95 : new Long[] {null, 100L, 10_000L}) {
            assertThat(ExecutionVerdict.decide(streamed, false, null, 1.0, maxP95))
                    .isEqualTo(ExecutionVerdict.decide(full, false, null, 1.0, maxP95));
        }
    }
}
