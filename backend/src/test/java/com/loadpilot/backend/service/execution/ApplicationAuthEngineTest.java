package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.enums.ApiKeyLocation;
import com.loadpilot.backend.enums.ApplicationAuthType;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.enums.HttpMethod;
import com.loadpilot.backend.service.auth.ApplicationAuthConfig;
import com.loadpilot.backend.service.auth.ApplicationAuthSpec;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Authentification de l'Application appliquee par le moteur, contre un vrai
 * serveur HTTP local (aucun mock) : connexion automatique LOGIN_TOKEN,
 * jeton fixe, cle API, Basic, priorite des en-tetes d'etape et variable
 * ${authToken}.
 */
class ApplicationAuthEngineTest {

    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final HttpClientExecutionEngine engine = new HttpClientExecutionEngine(2, new ObjectMapper(), EXECUTOR);
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private String startServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", handler);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return "http://localhost:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static StepExecutionSpec get(String url, String headers) {
        return new StepExecutionSpec(UUID.randomUUID(), "etape", HttpMethod.GET, url, headers, null, null,
                null, null, null, null, null, null);
    }

    private static ApplicationAuthSpec login(String path, String body) {
        return new ApplicationAuthSpec(ApplicationAuthType.LOGIN_TOKEN,
                new ApplicationAuthConfig(null, null, null, null, null, null, path, body, "$.data.token", null, null));
    }

    private ScenarioExecutionOutcome run(List<StepExecutionSpec> steps, String baseUrl, ApplicationAuthSpec auth, int vus) {
        return engine.execute(steps, baseUrl, auth, new LoadTestSpec(vus, 0, null, 1, 0, null),
                new RunningExecutionHandle(), List.of());
    }

    @Test
    void loginToken_logsInOnce_andSendsBearerTokenOnEveryRequest() throws IOException {
        AtomicInteger logins = new AtomicInteger();
        AtomicReference<String> loginBody = new AtomicReference<>();
        String base = startServer(ex -> {
            if (ex.getRequestURI().getPath().equals("/auth/login")) {
                logins.incrementAndGet();
                loginBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(ex, 200, "{\"data\":{\"token\":\"abc123\"}}");
            } else {
                boolean ok = "Bearer abc123".equals(ex.getRequestHeaders().getFirst("Authorization"));
                respond(ex, ok ? 200 : 401, "");
            }
        });

        ScenarioExecutionOutcome outcome = run(List.of(get("/secure", null)), base,
                login("/auth/login", "{\"user\":\"a\"}"), 3);

        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(outcome.stepOutcomes()).hasSize(3).allMatch(StepOutcome::success);
        assertThat(logins.get()).isEqualTo(1);
        assertThat(loginBody.get()).isEqualTo("{\"user\":\"a\"}");
    }

    @Test
    void loginToken_rejected_failsExecutionWithoutRunningSteps() throws IOException {
        AtomicInteger stepCalls = new AtomicInteger();
        String base = startServer(ex -> {
            if (ex.getRequestURI().getPath().equals("/auth/login")) {
                respond(ex, 401, "{\"error\":\"bad credentials\"}");
            } else {
                stepCalls.incrementAndGet();
                respond(ex, 200, "");
            }
        });

        ScenarioExecutionOutcome outcome = run(List.of(get("/secure", null)), base, login("/auth/login", "{}"), 2);

        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(outcome.stepOutcomes()).isEmpty();
        assertThat(outcome.errorMessage()).isEqualTo("Authentification : login refusé (401)");
        assertThat(stepCalls.get()).isZero();
    }

    /** Connexion automatique dont le jeton est lu dans un en-tete de la reponse de login. */
    private static ApplicationAuthSpec loginWithTokenInHeader(String headerSpec) {
        return new ApplicationAuthSpec(ApplicationAuthType.LOGIN_TOKEN, new ApplicationAuthConfig(null, null, null, null, null,
                null, "/auth/login", "{}", headerSpec, null, null, null, com.loadpilot.backend.enums.CaptureSource.HEADER));
    }

    @Test
    void loginToken_readFromResponseHeader_caseInsensitive_evenWithoutJsonBody() throws IOException {
        String base = startServer(ex -> {
            if (ex.getRequestURI().getPath().equals("/auth/login")) {
                ex.getResponseHeaders().add("X-Auth-Token", "hdr-token-1");
                ex.sendResponseHeaders(204, -1);
                ex.close();
            } else {
                boolean ok = "Bearer hdr-token-1".equals(ex.getRequestHeaders().getFirst("Authorization"));
                respond(ex, ok ? 200 : 401, "");
            }
        });

        ScenarioExecutionOutcome outcome = run(List.of(get("/secure", null)), base, loginWithTokenInHeader("x-auth-token"), 2);

        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(outcome.stepOutcomes()).hasSize(2).allMatch(StepOutcome::success);
    }

    @Test
    void loginToken_readFromNamedCookie() throws IOException {
        String base = startServer(ex -> {
            if (ex.getRequestURI().getPath().equals("/auth/login")) {
                ex.getResponseHeaders().add("Set-Cookie", "lang=fr; Path=/");
                ex.getResponseHeaders().add("Set-Cookie", "ACCESS=cookie-token-9; HttpOnly");
                respond(ex, 200, "{\"ok\":true}");
            } else {
                boolean ok = "Bearer cookie-token-9".equals(ex.getRequestHeaders().getFirst("Authorization"));
                respond(ex, ok ? 200 : 401, "");
            }
        });

        ScenarioExecutionOutcome outcome = run(List.of(get("/secure", null)), base, loginWithTokenInHeader("Set-Cookie:ACCESS"), 1);

        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.SUCCESS);
    }

    @Test
    void loginToken_headerAbsent_failsWithExplicitMessage() throws IOException {
        String base = startServer(ex -> respond(ex, 200, "{\"token\":\"in-the-body-only\"}"));

        ScenarioExecutionOutcome outcome = run(List.of(get("/secure", null)), base, loginWithTokenInHeader("X-Auth-Token"), 1);

        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(outcome.errorMessage()).isEqualTo("Authentification : token introuvable : en-tête « X-Auth-Token » absent de la réponse");
    }

    @Test
    void loginToken_tokenMissingFromResponse_failsWithExplicitMessage() throws IOException {
        String base = startServer(ex -> respond(ex, 200, "{\"ok\":true}"));

        ScenarioExecutionOutcome outcome = run(List.of(get("/secure", null)), base, login("/auth/login", "{}"), 1);

        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(outcome.errorMessage()).isEqualTo("Authentification : token introuvable à $.data.token : aucun champ « data » à la racine");
    }

    @Test
    void loginToken_exposesAuthTokenVariableToSteps() throws IOException {
        AtomicReference<String> seenPath = new AtomicReference<>();
        String base = startServer(ex -> {
            if (ex.getRequestURI().getPath().equals("/auth/login")) {
                respond(ex, 200, "{\"data\":{\"token\":\"tok-9\"}}");
            } else {
                seenPath.set(ex.getRequestURI().toString());
                respond(ex, 200, "");
            }
        });

        run(List.of(get("/echo?t=${authToken}", null)), base, login("/auth/login", "{}"), 1);

        assertThat(seenPath.get()).isEqualTo("/echo?t=tok-9");
    }

    @Test
    void apiKeyInQuery_isAppendedToEveryRequestUrl() throws IOException {
        AtomicReference<String> seenQuery = new AtomicReference<>();
        String base = startServer(ex -> {
            seenQuery.set(ex.getRequestURI().getQuery());
            respond(ex, 200, "");
        });
        ApplicationAuthSpec auth = new ApplicationAuthSpec(ApplicationAuthType.API_KEY,
                new ApplicationAuthConfig(null, "api_key", "k-1", ApiKeyLocation.QUERY, null, null, null, null, null, null, null));

        ScenarioExecutionOutcome outcome = run(List.of(get("/items?page=2", null)), base, auth, 1);

        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(seenQuery.get()).isEqualTo("page=2&api_key=k-1");
    }

    @Test
    void basicAuth_sendsEncodedCredentials() throws IOException {
        AtomicReference<String> seen = new AtomicReference<>();
        String base = startServer(ex -> {
            seen.set(ex.getRequestHeaders().getFirst("Authorization"));
            respond(ex, 200, "");
        });
        ApplicationAuthSpec auth = new ApplicationAuthSpec(ApplicationAuthType.BASIC,
                new ApplicationAuthConfig(null, null, null, null, "agent001", "test123", null, null, null, null, null));

        run(List.of(get("/x", null)), base, auth, 1);

        String expected = "Basic " + Base64.getEncoder().encodeToString("agent001:test123".getBytes(StandardCharsets.UTF_8));
        assertThat(seen.get()).isEqualTo(expected);
    }

    @Test
    void stepHeader_overridesApplicationAuthHeader_caseInsensitive() throws IOException {
        AtomicReference<String> seen = new AtomicReference<>();
        String base = startServer(ex -> {
            seen.set(ex.getRequestHeaders().getFirst("Authorization"));
            respond(ex, 200, "");
        });
        ApplicationAuthSpec auth = new ApplicationAuthSpec(ApplicationAuthType.BEARER_TOKEN,
                new ApplicationAuthConfig("app-token", null, null, null, null, null, null, null, null, null, null));

        run(List.of(get("/x", "{\"authorization\":\"Bearer step-token\"}")), base, auth, 1);

        assertThat(seen.get()).isEqualTo("Bearer step-token");
    }

    @Test
    void noAuth_legacySignature_sendsNoAuthorizationHeader() throws IOException {
        AtomicReference<String> seen = new AtomicReference<>("unset");
        String base = startServer(ex -> {
            seen.set(ex.getRequestHeaders().getFirst("Authorization"));
            respond(ex, 200, "");
        });

        engine.execute(List.of(get("/x", null)), base, LoadTestSpec.singlePass(), new RunningExecutionHandle(), List.of());

        assertThat(seen.get()).isNull();
    }

    @Test
    void loginToken_nonJsonResponse_failsWithExplicitMessage_withoutLeakingBody() throws IOException {
        String base = startServer(ex -> {
            byte[] html = "<html>secret-session=abc</html>".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, html.length);
            ex.getResponseBody().write(html);
            ex.close();
        });

        ScenarioExecutionOutcome outcome = run(List.of(get("/secure", null)), base, login("/auth/login", "{}"), 1);

        assertThat(outcome.errorMessage()).isEqualTo("Authentification : réponse de login non JSON (Content-Type text/html)");
        assertThat(outcome.errorMessage()).doesNotContain("secret-session");
    }

    @Test
    void loginToken_unreachableEndpoint_failsWithReadableMessage() {
        // Port 1 : ferme sur toute machine de dev/CI -> connexion refusee.
        ScenarioExecutionOutcome outcome = run(List.of(get("/secure", null)), "http://localhost:1",
                login("/auth/login", "{\"password\":\"s3cret\"}"), 1);

        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(outcome.errorMessage()).startsWith("Authentification : login impossible, ")
                .contains("/auth/login").doesNotContain("s3cret");
    }

    @Test
    void loginToken_manyVirtualUsers_shareOneLoginAndOneToken() throws IOException {
        AtomicInteger logins = new AtomicInteger();
        AtomicInteger authorized = new AtomicInteger();
        String base = startServer(ex -> {
            if (ex.getRequestURI().getPath().equals("/auth/login")) {
                respond(ex, 200, "{\"data\":{\"token\":\"t-" + logins.incrementAndGet() + "\"}}");
            } else {
                if ("Bearer t-1".equals(ex.getRequestHeaders().getFirst("Authorization"))) authorized.incrementAndGet();
                respond(ex, 200, "");
            }
        });

        ScenarioExecutionOutcome outcome = engine.execute(List.of(get("/a", null), get("/b", null)), base,
                login("/auth/login", "{}"), new LoadTestSpec(10, 0, null, 2, 0, null), new RunningExecutionHandle(), List.of());

        assertThat(logins.get()).isEqualTo(1);
        assertThat(outcome.stepOutcomes()).hasSize(40).allMatch(StepOutcome::success);
        assertThat(authorized.get()).isEqualTo(40);
    }
}
