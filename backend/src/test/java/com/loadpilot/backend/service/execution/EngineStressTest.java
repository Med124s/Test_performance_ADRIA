package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.enums.CsvDistribution;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.enums.HttpMethod;
import com.loadpilot.backend.service.auth.ApplicationAuthSpec;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Mode stress dans le moteur : debit de la baseline, reservoir agrandi selon le deficit, arret propre a la limite. */
class EngineStressTest {

    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static HttpServer server;
    private static String baseUrl;

    private final HttpClientExecutionEngine engine = new HttpClientExecutionEngine(5, new ObjectMapper(), EXECUTOR);

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/fast", ex -> {
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        // Reponse en 1,5 s : un seul VU ne peut pas tenir 2 req/s.
        server.createContext("/slow", ex -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
        EXECUTOR.shutdown();
    }

    private ScenarioExecutionOutcome run(String path, StressRamp.Limits limits, RunningExecutionHandle handle) {
        List<StepExecutionSpec> steps = List.of(new StepExecutionSpec(UUID.randomUUID(), "etape", HttpMethod.GET, path,
                null, null, null, null, null, null, null, null, null));
        return engine.execute(steps, baseUrl, ApplicationAuthSpec.none(), LoadTestSpec.stress(limits, 0), handle,
                new VariableSources(List.of(), CsvDistribution.PER_VIRTUAL_USER, Map.of(), steps));
    }

    @Test
    void baseline_sendsTwoRequestsPerSecond_withOneVirtualUser_thenStopsAtTheMaximumDuration() {
        RunningExecutionHandle handle = new RunningExecutionHandle();
        ScenarioExecutionOutcome outcome = run("/fast", new StressRamp.Limits(200, 500, 3), handle);

        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(outcome.summary().total()).isBetween(4L, 9L);
        assertThat(handle.isCancelled()).isFalse();
        assertThat(handle.stressRamp().limitReached()).isEqualTo(StressRamp.Limit.DURATION);
        assertThat(handle.stressRamp().peakVirtualUsers()).isEqualTo(1);
        assertThat(handle.liveStats().loadLevelAt(1).offeredRps()).isEqualTo(StressSettings.BASELINE_RPS);
    }

    @Test
    void slowTarget_growsThePool_andTheStop_letsInFlightRequestsFinish() {
        RunningExecutionHandle handle = new RunningExecutionHandle();
        ScenarioExecutionOutcome outcome = run("/slow", new StressRamp.Limits(200, 500, 5), handle);

        assertThat(handle.stressRamp().peakVirtualUsers()).isGreaterThan(1);
        // Aucune requete coupee par l'arret : toutes ont recu leur reponse.
        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(outcome.stepOutcomes()).allSatisfy(o -> assertThat(o.httpStatus()).isEqualTo(200));
    }

    @Test
    void cancellation_endsTheRamp() throws Exception {
        RunningExecutionHandle handle = new RunningExecutionHandle();
        Thread canceller = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            handle.requestCancellation();
        });
        long start = System.nanoTime();
        run("/fast", new StressRamp.Limits(200, 500, 60), handle);
        canceller.join();

        assertThat((System.nanoTime() - start) / 1_000_000_000L).isLessThan(10);
        assertThat(handle.stressRamp().limitReached()).isNull();
    }
}
