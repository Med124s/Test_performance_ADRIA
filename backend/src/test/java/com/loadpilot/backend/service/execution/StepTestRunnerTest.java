package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.dto.response.TestRunResponse;
import com.loadpilot.backend.dto.response.TestRunResponse.StepResult;
import com.loadpilot.backend.enums.ApplicationAuthType;
import com.loadpilot.backend.enums.HttpMethod;
import com.loadpilot.backend.service.auth.ApplicationAuthConfig;
import com.loadpilot.backend.service.auth.ApplicationAuthSpec;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Test detaille contre un vrai serveur HTTP local qui imite banking-test-api. */
class StepTestRunnerTest {

    private static final String TOKEN = "TOKEN-ABC-123456";
    private static final String LOGIN_PASSWORD = "s3cret-pw";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static HttpServer server;
    private static String baseUrl;
    private static final List<String> hits = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", StepTestRunnerTest::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @BeforeEach
    void clearHits() {
        hits.clear();
    }

    private static void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        hits.add(method + " " + ex.getRequestURI());
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        boolean authorized = ("Bearer " + TOKEN).equals(auth);
        if (method.equals("POST") && path.equals("/login")) {
            if (body.contains(LOGIN_PASSWORD)) {
                ex.getResponseHeaders().add("Set-Cookie", "session=abc; HttpOnly");
                json(ex, 200, "{\"success\":true,\"token\":\"" + TOKEN + "\",\"user\":\"agent\"}");
            } else {
                json(ex, 401, "{\"error\":\"bad credentials\"}");
            }
        } else if (path.equals("/balance")) {
            json(ex, authorized ? 200 : 401, authorized ? "{\"success\":true,\"balance\":1500}" : "{\"error\":\"unauthorized\"}");
        } else if (method.equals("POST") && path.equals("/transfers")) {
            json(ex, 201, "{\"transferId\":\"TRF42\",\"status\":\"completed\",\"echo\":" + body + "}");
        } else if (method.equals("PATCH") && path.equals("/transfers/TRF42")) {
            json(ex, 200, "{\"success\":true,\"transferId\":\"TRF42\"}");
        } else if (path.equals("/big")) {
            byte[] big = "a".repeat(200 * 1024).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/plain");
            ex.sendResponseHeaders(200, big.length);
            ex.getResponseBody().write(big);
            ex.close();
        } else if (path.equals("/slow")) {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            json(ex, 200, "{\"slow\":true}");
        } else {
            json(ex, 404, "{\"error\":\"not found\"}");
        }
    }

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static StepExecutionSpec step(String name, HttpMethod method, String url, String headers, String body,
            Integer expected, String contains, String captureName, String capturePath, Integer timeout) {
        return new StepExecutionSpec(UUID.randomUUID(), name, method, url, headers, body, expected, null, timeout,
                null, contains, captureName, capturePath);
    }

    private static ApplicationAuthSpec loginAuth(String password) {
        return new ApplicationAuthSpec(ApplicationAuthType.LOGIN_TOKEN, new ApplicationAuthConfig(null, null, null, null,
                null, null, "/login", "{\"username\":\"agent\",\"password\":\"" + password + "\"}", "$.token", null, null));
    }

    private static StepTestRunner runner(long globalSeconds) {
        return new StepTestRunner(10, globalSeconds, MAPPER);
    }

    private static TestRunPlan plan(ApplicationAuthSpec auth, Map<String, String> variables, StepExecutionSpec... steps) {
        return new TestRunPlan(UUID.randomUUID(), baseUrl, auth, List.of(steps), variables);
    }

    @Test
    void bankingFlow_loginOnce_captureAndReuseVariable_withDetailedRequestAndResponse() throws Exception {
        TestRunResponse result = runner(60).run(plan(loginAuth(LOGIN_PASSWORD), Map.of("amount", "250"),
                step("Solde", HttpMethod.GET, "/balance", null, null, 200, "\"success\":true", null, null, null),
                step("Virement", HttpMethod.POST, "/transfers", "{\"Content-Type\":\"application/json\"}",
                        "{\"amount\": ${amount}, \"password\": \"pin-9876\"}", 201, "completed", "transferId", "$.transferId", null),
                step("Modifier", HttpMethod.PATCH, "/transfers/${transferId}", "{\"Content-Type\":\"application/json\"}",
                        "{\"amount\": 300}", 200, null, null, null, null)), StepTestRunner.Listener.NONE);

        assertThat(result.success()).isTrue();
        assertThat(result.passedSteps()).isEqualTo(3);
        assertThat(result.auth().success()).isTrue();
        assertThat(hits.stream().filter(h -> h.startsWith("POST /login")).count()).isEqualTo(1);

        StepResult balance = result.steps().get(0);
        assertThat(balance.request().url()).isEqualTo(baseUrl + "/balance");
        assertThat(balance.request().headers()).containsEntry("Authorization", TestRunMasking.MASK);
        assertThat(balance.response().status()).isEqualTo(200);
        assertThat(balance.response().statusText()).isEqualTo("OK");
        assertThat(balance.response().body()).contains("\"balance\":1500");
        assertThat(balance.response().json()).isTrue();
        assertThat(balance.response().sizeBytes()).isEqualTo("{\"success\":true,\"balance\":1500}".length());
        assertThat(balance.checks().status().ok()).isTrue();
        assertThat(balance.checks().assertion().ok()).isTrue();

        StepResult transfer = result.steps().get(1);
        assertThat(transfer.request().body()).contains("\"amount\": 250").contains("\"password\": \"••••••\"")
                .doesNotContain("pin-9876");
        assertThat(transfer.response().body()).doesNotContain("pin-9876");
        assertThat(transfer.checks().capture().found()).isTrue();
        assertThat(transfer.checks().capture().value()).isEqualTo("TRF42");

        StepResult patch = result.steps().get(2);
        assertThat(patch.request().method()).isEqualTo("PATCH");
        assertThat(patch.request().url()).isEqualTo(baseUrl + "/transfers/TRF42");
        assertThat(patch.status()).isEqualTo(TestRunResponse.PASSED);

        assertThat(result.variables()).containsEntry("transferId", "TRF42").doesNotContainKey("authToken");
        String json = MAPPER.writeValueAsString(result);
        assertThat(json).doesNotContain(TOKEN).doesNotContain(LOGIN_PASSWORD);
    }

    @Test
    void failedStep_stopsTheRun_andFollowingStepsAreNotExecuted() {
        TestRunResponse result = runner(60).run(plan(loginAuth(LOGIN_PASSWORD), Map.of(),
                step("Inconnu", HttpMethod.GET, "/nope", null, null, 200, null, null, null, null),
                step("Solde", HttpMethod.GET, "/balance", null, null, 200, null, null, null, null)),
                StepTestRunner.Listener.NONE);

        assertThat(result.success()).isFalse();
        StepResult first = result.steps().get(0);
        assertThat(first.status()).isEqualTo(TestRunResponse.FAILED);
        assertThat(first.errorKind()).isEqualTo("HTTP_STATUS");
        assertThat(first.error()).contains("404 Not Found").contains("attendu 200");
        assertThat(first.response().body()).contains("not found");
        assertThat(result.steps().get(1).status()).isEqualTo(TestRunResponse.SKIPPED);
        assertThat(result.steps().get(1).errorKind()).isEqualTo("PREVIOUS_FAILED");
        assertThat(hits).noneMatch(h -> h.contains("/balance"));
    }

    @Test
    void status200_butUnexpectedContent_isAFailure_withAClearMessage() {
        TestRunResponse result = runner(60).run(plan(loginAuth(LOGIN_PASSWORD), Map.of(),
                step("Modifier", HttpMethod.PATCH, "/transfers/TRF42", null, null, 200, "\"success\":false", null, null, null)),
                StepTestRunner.Listener.NONE);

        StepResult s = result.steps().get(0);
        assertThat(s.response().status()).isEqualTo(200);
        assertThat(s.checks().status().ok()).isTrue();
        assertThat(s.checks().assertion().ok()).isFalse();
        assertThat(s.status()).isEqualTo(TestRunResponse.FAILED);
        assertThat(s.errorKind()).isEqualTo("ASSERTION");
        assertThat(s.error()).isEqualTo("Contenu attendu introuvable : \"success\":false");
        assertThat(result.success()).isFalse();
    }

    @Test
    void stepHeader_overridesApplicationAuth_likeTheEngine() {
        TestRunResponse result = runner(60).run(plan(loginAuth(LOGIN_PASSWORD), Map.of(),
                step("Solde", HttpMethod.GET, "/balance", "{\"Authorization\":\"Bearer wrong\"}", null, 200, null, null, null, null)),
                StepTestRunner.Listener.NONE);

        StepResult s = result.steps().get(0);
        assertThat(s.response().status()).isEqualTo(401);
        assertThat(s.response().statusText()).isEqualTo("Unauthorized");
        assertThat(s.request().headers()).containsEntry("Authorization", TestRunMasking.MASK).hasSize(1);
    }

    @Test
    void loginFailure_marksEveryStepNotExecuted() {
        TestRunResponse result = runner(60).run(plan(loginAuth("wrong-password"), Map.of(),
                step("Solde", HttpMethod.GET, "/balance", null, null, 200, null, null, null, null)),
                StepTestRunner.Listener.NONE);

        assertThat(result.auth().success()).isFalse();
        assertThat(result.auth().message()).contains("401");
        assertThat(result.steps().get(0).status()).isEqualTo(TestRunResponse.SKIPPED);
        assertThat(result.steps().get(0).errorKind()).isEqualTo("AUTH_FAILED");
        assertThat(result.executedSteps()).isZero();
    }

    @Test
    void unresolvedVariableInUrl_failsWithoutSendingTheRequest() {
        TestRunResponse result = runner(60).run(plan(ApplicationAuthSpec.none(), Map.of(),
                step("Modifier", HttpMethod.PATCH, "/transfers/${transferId}", null, null, 200, null, null, null, null)),
                StepTestRunner.Listener.NONE);

        StepResult s = result.steps().get(0);
        assertThat(s.errorKind()).isEqualTo("UNRESOLVED_VARIABLE");
        assertThat(s.error()).contains("${transferId}");
        assertThat(s.response()).isNull();
        assertThat(hits).isEmpty();
    }

    @Test
    void captureNotFound_isReportedWithPath() {
        TestRunResponse result = runner(60).run(plan(loginAuth(LOGIN_PASSWORD), Map.of(),
                step("Solde", HttpMethod.GET, "/balance", null, null, 200, null, "id", "$.data.id", null)),
                StepTestRunner.Listener.NONE);

        var capture = result.steps().get(0).checks().capture();
        assertThat(capture.found()).isFalse();
        assertThat(capture.message()).isEqualTo("Introuvable à $.data.id.");
    }

    @Test
    void largeBody_isTruncatedTo100Kb_butRealSizeIsReported() {
        TestRunResponse result = runner(60).run(plan(ApplicationAuthSpec.none(), Map.of(),
                step("Gros", HttpMethod.GET, "/big", null, null, 200, null, null, null, null)),
                StepTestRunner.Listener.NONE);

        var response = result.steps().get(0).response();
        assertThat(response.truncated()).isTrue();
        assertThat(response.sizeBytes()).isEqualTo(200 * 1024);
        assertThat(response.body()).hasSize(StepTestRunner.DISPLAY_BODY_LIMIT_BYTES);
        assertThat(response.json()).isFalse();
    }

    @Test
    void connectionRefused_isClassified() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        TestRunPlan refused = new TestRunPlan(UUID.randomUUID(), "http://localhost:" + closedPort, ApplicationAuthSpec.none(),
                List.of(step("Santé", HttpMethod.GET, "/health", null, null, null, null, null, null, null)), Map.of());

        StepResult s = runner(60).run(refused, StepTestRunner.Listener.NONE).steps().get(0);
        assertThat(s.errorKind()).isEqualTo("CONNECTION_REFUSED");
        assertThat(s.error()).contains("onnexion refusée").contains("localhost:" + closedPort);
    }

    @Test
    void stepTimeout_isReported() {
        StepResult s = runner(60).run(plan(ApplicationAuthSpec.none(), Map.of(),
                step("Lent", HttpMethod.GET, "/slow", null, null, 200, null, null, null, 1)),
                StepTestRunner.Listener.NONE).steps().get(0);

        assertThat(s.errorKind()).isEqualTo("TIMEOUT");
        assertThat(s.error()).contains("1 s");
    }

    @Test
    void globalTimeout_stopsTheTest_andSkipsRemainingSteps() {
        TestRunResponse result = runner(2).run(plan(ApplicationAuthSpec.none(), Map.of(),
                step("Lent 1", HttpMethod.GET, "/slow", null, null, 200, null, null, null, null),
                step("Lent 2", HttpMethod.GET, "/slow", null, null, 200, null, null, null, null),
                step("Lent 3", HttpMethod.GET, "/slow", null, null, 200, null, null, null, null)),
                StepTestRunner.Listener.NONE);

        assertThat(result.timedOut()).isTrue();
        assertThat(result.steps().get(0).status()).isEqualTo(TestRunResponse.PASSED);
        assertThat(result.steps().get(1).errorKind()).isEqualTo("GLOBAL_TIMEOUT");
        assertThat(result.steps().get(2).status()).isEqualTo(TestRunResponse.SKIPPED);
        assertThat(result.totalDurationMs()).isLessThan(4000);
    }

    @Test
    void responseSetCookie_isMasked() {
        TestRunResponse result = runner(60).run(plan(ApplicationAuthSpec.none(), Map.of(),
                step("Login", HttpMethod.POST, "/login", null, "{\"password\":\"" + LOGIN_PASSWORD + "\"}", 200, null,
                        "token", "$.token", null)), StepTestRunner.Listener.NONE);

        StepResult s = result.steps().get(0);
        assertThat(s.response().headers()).containsEntry("set-cookie", TestRunMasking.MASK);
        assertThat(s.request().body()).doesNotContain(LOGIN_PASSWORD);
        // Jeton capture dans une variable "token" : masque partout, jamais reutilisable tel quel.
        assertThat(s.checks().capture().masked()).isTrue();
        assertThat(s.response().body()).doesNotContain(TOKEN);
        assertThat(result.variables()).doesNotContainKey("token");
    }

    @Test
    void urlWarning_onlyForAbsoluteUrlOnAnotherOrigin() {
        var warning = StepTestRunner.urlWarning("http://localhost:8888", "http://localhost:8000/api/auth/login");
        assertThat(warning).isNotNull();
        assertThat(warning.suggestedPath()).isEqualTo("/api/auth/login");
        assertThat(warning.message()).contains("http://localhost:8000").contains("http://localhost:8888");

        assertThat(StepTestRunner.urlWarning("http://localhost:8888", "/api/auth/login")).isNull();
        assertThat(StepTestRunner.urlWarning("http://localhost:8888", "http://LOCALHOST:8888/x")).isNull();
        assertThat(StepTestRunner.urlWarning("http://api.test", "http://api.test:80/x")).isNull();
        assertThat(StepTestRunner.urlWarning("http://h:1/base", "http://h:2/base/x?y=1").suggestedPath()).isEqualTo("/x?y=1");
    }

    @Test
    void masking_helpers() {
        assertThat(TestRunMasking.maskBody("{\"username\":\"a\",\"password\":\"p\",\"accessToken\":12}", Set.of()))
                .isEqualTo("{\"username\":\"a\",\"password\":\"••••••\",\"accessToken\":\"••••••\"}");
        assertThat(TestRunMasking.maskUrl("http://h/x?api_key=K1&page=2", Set.of(), Set.of()))
                .isEqualTo("http://h/x?api_key=••••••&page=2");
        assertThat(TestRunMasking.maskHeaders(Map.of("X-API-Key", "v"), Set.of(), Set.of()))
                .containsEntry("X-API-Key", TestRunMasking.MASK);
    }
}
