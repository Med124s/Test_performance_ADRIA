package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.enums.CsvDistribution;
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

/**
 * Une execution annulee (manuellement, frein, pause trop longue, limite memoire) garde les resultats
 * deja obtenus par ses utilisateurs virtuels : avant, Future.cancel(true) les faisait tous perdre.
 * Les requetes coupees par l'annulation ne sont pas comptees comme des echecs.
 */
class EngineCancellationResultsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static HttpServer server;
    private static String baseUrl;

    private final HttpClientExecutionEngine engine = new HttpClientExecutionEngine(5, MAPPER, EXECUTOR);

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", ex -> {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
        new HttpClientExecutionEngine(2, MAPPER, EXECUTOR).warmUpHttpClient();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
        EXECUTOR.shutdown();
    }

    @Test
    void cancelledRun_keepsTheResultsAlreadyObtained_withoutInterruptedFailures() throws Exception {
        List<StepExecutionSpec> steps = List.of(new StepExecutionSpec(UUID.randomUUID(), "Appel", HttpMethod.GET, "/ok",
                null, null, null, null, null, null, null, null, null));
        RunningExecutionHandle handle = new RunningExecutionHandle();
        Thread canceller = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            handle.requestCancellationBy("alice");
        });

        long started = System.currentTimeMillis();
        ScenarioExecutionOutcome outcome = engine.execute(steps, baseUrl, ApplicationAuthSpec.none(),
                new LoadTestSpec(4, 0, 30, null, 0, null), handle,
                new VariableSources(List.of(), CsvDistribution.PER_VIRTUAL_USER, Map.of(), steps));
        canceller.join();

        assertThat(System.currentTimeMillis() - started).as("arret rapide malgre 30 s prevues").isLessThan(8000);
        // ~0,8 s a 4 VU et ~20 ms par requete : des dizaines de resultats, tous de vraies reponses.
        assertThat(outcome.stepOutcomes()).hasSizeGreaterThan(20)
                .allSatisfy(o -> assertThat(o.httpStatus()).isEqualTo(200));
        // Chaque VU a contribue, et le suivi en direct compte exactement les memes resultats.
        assertThat(outcome.stepOutcomes()).extracting(StepOutcome::virtualUser).containsOnly(0, 1, 2, 3).contains(0, 1, 2, 3);
        assertThat(handle.liveStats().snapshot().requests()).isEqualTo(outcome.stepOutcomes().size());
    }

    @Test
    void cancelledBeforeAnyRequest_stillEmpty_andReturnsImmediately() {
        List<StepExecutionSpec> steps = List.of(new StepExecutionSpec(UUID.randomUUID(), "Appel", HttpMethod.GET, "/ok",
                null, null, null, null, null, null, null, null, null));
        RunningExecutionHandle handle = new RunningExecutionHandle();
        handle.requestCancellation();
        long started = System.currentTimeMillis();
        ScenarioExecutionOutcome outcome = engine.execute(steps, baseUrl, ApplicationAuthSpec.none(),
                new LoadTestSpec(3, 0, null, 5, 0, null), handle,
                new VariableSources(List.of(), CsvDistribution.PER_VIRTUAL_USER, Map.of(), steps));
        assertThat(outcome.stepOutcomes()).isEmpty();
        assertThat(System.currentTimeMillis() - started).isLessThan(3000);
    }
}
