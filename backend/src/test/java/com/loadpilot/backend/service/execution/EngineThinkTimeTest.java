package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.enums.CsvDistribution;
import com.loadpilot.backend.enums.HttpMethod;
import com.loadpilot.backend.service.auth.ApplicationAuthSpec;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Think time dans le moteur : fixe sans variation (comportement d'avant), varie avec une variation. */
class EngineThinkTimeTest {

    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static HttpServer server;
    private static String baseUrl;
    private static final List<Long> arrivals = Collections.synchronizedList(new ArrayList<>());

    private final HttpClientExecutionEngine engine = new HttpClientExecutionEngine(5, new ObjectMapper(), EXECUTOR);

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", ex -> {
            arrivals.add(System.nanoTime());
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

    @BeforeEach
    void reset() {
        arrivals.clear();
    }

    /** Ecarts (ms) entre deux requetes successives d'un seul VU, think time de scenario de 200 ms. */
    private List<Long> gaps(int variationPercent) {
        List<StepExecutionSpec> steps = List.of(new StepExecutionSpec(UUID.randomUUID(), "ok", HttpMethod.GET, "/ok",
                null, null, null, null, null, null, null, null, null));
        engine.execute(steps, baseUrl, ApplicationAuthSpec.none(),
                new LoadTestSpec(1, 0, null, 7, 200, null, ThrottlePolicy.DEFAULT, variationPercent), new RunningExecutionHandle(),
                new VariableSources(List.of(), CsvDistribution.PER_VIRTUAL_USER, Map.of(), steps));
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < arrivals.size(); i++) gaps.add((arrivals.get(i) - arrivals.get(i - 1)) / 1_000_000L);
        return gaps;
    }

    @Test
    void withoutVariation_theThinkTimeStaysFixed() {
        List<Long> gaps = gaps(0);
        assertThat(gaps).hasSize(6).allSatisfy(g -> assertThat(g).isBetween(195L, 330L));
    }

    @Test
    void withVariation_theThinkTimeVaries_withinItsBounds() {
        List<Long> gaps = gaps(50); // 100 a 300 ms
        assertThat(gaps).hasSize(6).allSatisfy(g -> assertThat(g).isBetween(95L, 430L));
        long spread = Collections.max(gaps) - Collections.min(gaps);
        assertThat(spread).as("les pauses ne sont plus toutes identiques").isGreaterThan(20L);
    }
}
