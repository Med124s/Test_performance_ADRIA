package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.dto.response.TestRunResponse;
import com.loadpilot.backend.dto.response.TestRunResponse.StepResult;
import com.loadpilot.backend.enums.CaptureSource;
import com.loadpilot.backend.enums.CsvDistribution;
import com.loadpilot.backend.enums.HttpMethod;
import com.loadpilot.backend.enums.JsonAssertionOperator;
import com.loadpilot.backend.service.auth.ApplicationAuthSpec;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Temps de reponse maximal et assertion JSON, dans le moteur de charge et dans « Tester » : controles
 * optionnels qui s'ajoutent au code HTTP et au contenu attendu, sans changer une etape qui n'en a pas.
 */
class EngineStepChecksTest {

    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static HttpServer server;
    private static String baseUrl;

    private final HttpClientExecutionEngine engine = new HttpClientExecutionEngine(5, MAPPER, EXECUTOR);

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/fast", ex -> json(ex, "{\"status\":\"OK\",\"items\":[\"a\",\"b\"]}"));
        server.createContext("/slow", ex -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            json(ex, "{\"status\":\"OK\"}");
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

    private static void json(HttpExchange ex, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static StepExecutionSpec step(String url, String contains, Integer maxMs, String path,
            JsonAssertionOperator op, String value) {
        return new StepExecutionSpec(UUID.randomUUID(), "etape", HttpMethod.GET, url, null, null, 200, null, null, null,
                contains, null, null, null, null, (CaptureSource) null, maxMs, path, op, value);
    }

    private StepOutcome runOnce(StepExecutionSpec step) {
        List<StepExecutionSpec> steps = List.of(step);
        ScenarioExecutionOutcome outcome = engine.execute(steps, baseUrl, ApplicationAuthSpec.none(),
                new LoadTestSpec(1, 0, null, 1, 0, null, ThrottlePolicy.DEFAULT, 0), new RunningExecutionHandle(),
                new VariableSources(List.of(), CsvDistribution.PER_VIRTUAL_USER, Map.of(), steps));
        assertThat(outcome.stepOutcomes()).hasSize(1);
        return outcome.stepOutcomes().get(0);
    }

    @Test
    void withoutTheNewChecks_theStepBehavesAsBefore() {
        StepOutcome o = runOnce(step("/slow", null, null, null, null, null));
        assertThat(o.success()).isTrue();
        assertThat(o.error()).isNull();
    }

    @Test
    void responseTimeAboveTheMaximum_failsTheStep_withTheMeasuredTime() {
        StepOutcome slow = runOnce(step("/slow", null, 100, null, null, null));
        assertThat(slow.success()).isFalse();
        assertThat(slow.httpStatus()).isEqualTo(200);
        assertThat(slow.error()).startsWith("Temps de réponse trop long : ").endsWith("(maximum autorisé 100 ms)");

        assertThat(runOnce(step("/fast", null, 5_000, null, null, null)).success()).isTrue();
    }

    @Test
    void jsonAssertion_equalsAndContains_inAdditionToTheContentCheck() {
        assertThat(runOnce(step("/fast", "items", null, "$.status", JsonAssertionOperator.EQUALS, "OK")).success()).isTrue();
        assertThat(runOnce(step("/fast", null, null, "$.items", JsonAssertionOperator.CONTAINS, "\"b\"")).success()).isTrue();

        StepOutcome wrong = runOnce(step("/fast", null, null, "$.status", JsonAssertionOperator.EQUALS, "KO"));
        assertThat(wrong.success()).isFalse();
        assertThat(wrong.error()).isEqualTo("Assertion JSON : $.status vaut « OK », attendu « KO »");

        // Le contenu attendu reste verifie (et son message prioritaire) : rien n'est remplace.
        StepOutcome content = runOnce(step("/fast", "absent", null, "$.status", JsonAssertionOperator.EQUALS, "OK"));
        assertThat(content.success()).isFalse();
        assertThat(content.error()).startsWith("Contenu attendu introuvable");
    }

    @Test
    void tester_reportsBothChecks_andFailsLikeTheEngine() {
        StepTestRunner runner = new StepTestRunner(10, 60, MAPPER);
        TestRunResponse ok = runner.run(new TestRunPlan(UUID.randomUUID(), baseUrl, ApplicationAuthSpec.none(),
                List.of(step("/fast", null, 5_000, "$.status", JsonAssertionOperator.EQUALS, "OK")), Map.of()),
                StepTestRunner.Listener.NONE);
        StepResult passed = ok.steps().get(0);
        assertThat(passed.success()).isTrue();
        assertThat(passed.checks().responseTime().ok()).isTrue();
        assertThat(passed.checks().responseTime().maxMs()).isEqualTo(5_000);
        assertThat(passed.checks().json().ok()).isTrue();
        assertThat(passed.checks().json().operator()).isEqualTo("EQUALS");

        TestRunResponse ko = runner.run(new TestRunPlan(UUID.randomUUID(), baseUrl, ApplicationAuthSpec.none(),
                List.of(step("/slow", null, 100, "$.status", JsonAssertionOperator.CONTAINS, "KO")), Map.of()),
                StepTestRunner.Listener.NONE);
        StepResult failed = ko.steps().get(0);
        assertThat(failed.success()).isFalse();
        assertThat(failed.errorKind()).isEqualTo("ASSERTION");
        assertThat(failed.checks().json().ok()).isFalse();
        assertThat(failed.checks().json().message()).contains("ne contient pas « KO »");
        assertThat(failed.checks().responseTime().ok()).isFalse();
        assertThat(failed.checks().responseTime().actualMs()).isGreaterThan(100L);

        TestRunResponse plain = runner.run(new TestRunPlan(UUID.randomUUID(), baseUrl, ApplicationAuthSpec.none(),
                List.of(step("/slow", null, null, null, null, null)), Map.of()), StepTestRunner.Listener.NONE);
        assertThat(plain.steps().get(0).checks().responseTime()).isNull();
        assertThat(plain.steps().get(0).checks().json()).isNull();

        TestRunResponse timeOnly = runner.run(new TestRunPlan(UUID.randomUUID(), baseUrl, ApplicationAuthSpec.none(),
                List.of(step("/slow", null, 100, null, null, null)), Map.of()), StepTestRunner.Listener.NONE);
        assertThat(timeOnly.steps().get(0).errorKind()).isEqualTo("RESPONSE_TIME");
        assertThat(timeOnly.steps().get(0).error()).startsWith("Temps de réponse trop long");
    }
}
