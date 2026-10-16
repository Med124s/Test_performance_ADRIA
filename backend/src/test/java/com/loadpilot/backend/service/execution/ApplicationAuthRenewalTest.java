package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.enums.ApplicationAuthType;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.enums.HttpMethod;
import com.loadpilot.backend.enums.RequestDetailMode;
import com.loadpilot.backend.service.auth.ApplicationAuthConfig;
import com.loadpilot.backend.service.auth.ApplicationAuthSpec;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Correction L5 - renouvellement du jeton de l'application testee (« Connexion automatique »)
 * quand il expire pendant une Execution, contre un vrai serveur HTTP local (aucun mock) :
 * 401 -> reconnexion -> rejeu unique, echec de reconnexion sans boucle, un seul renouvellement
 * pour plusieurs VUs simultanes, et aucun changement pour les cas non concernes.
 */
class ApplicationAuthRenewalTest {

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

    private static boolean isLogin(HttpExchange ex) {
        return ex.getRequestURI().getPath().equals("/auth/login");
    }

    private static String bearer(HttpExchange ex) {
        return ex.getRequestHeaders().getFirst("Authorization");
    }

    private static StepExecutionSpec get(String url, String headers, Integer expectedStatus) {
        return new StepExecutionSpec(UUID.randomUUID(), "etape", HttpMethod.GET, url, headers, null, expectedStatus,
                null, null, null, null, null, null);
    }

    private static ApplicationAuthSpec loginToken() {
        return new ApplicationAuthSpec(ApplicationAuthType.LOGIN_TOKEN,
                new ApplicationAuthConfig(null, null, null, null, null, null, "/auth/login", "{}", "$.data.token", null, null));
    }

    private ScenarioExecutionOutcome run(List<StepExecutionSpec> steps, String base, ApplicationAuthSpec auth, int vus, int iterations,
                                         RunningExecutionHandle handle) {
        return engine.execute(steps, base, auth, new LoadTestSpec(vus, 0, null, iterations, 0, null), handle, List.of());
    }

    // ------------------------------------------------------------------ cas nominal

    @Test
    void expiredToken_401_thenRenewal_thenSingleReplay_succeeds() throws IOException {
        AtomicInteger logins = new AtomicInteger();
        AtomicInteger secureCalls = new AtomicInteger();
        AtomicInteger acceptedWithFirstToken = new AtomicInteger();
        String base = startServer(ex -> {
            if (isLogin(ex)) {
                respond(ex, 200, "{\"data\":{\"token\":\"t-" + logins.incrementAndGet() + "\"}}");
                return;
            }
            secureCalls.incrementAndGet();
            String auth = bearer(ex);
            // t-1 expire apres 2 requetes acceptees ; t-2 est toujours valide.
            boolean ok = "Bearer t-2".equals(auth)
                    || ("Bearer t-1".equals(auth) && acceptedWithFirstToken.incrementAndGet() <= 2);
            respond(ex, ok ? 200 : 401, "");
        });

        ScenarioExecutionOutcome outcome = run(List.of(get("/secure", null, null)), base, loginToken(), 1, 4,
                new RunningExecutionHandle());

        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(outcome.stepOutcomes()).hasSize(4).allMatch(StepOutcome::success);
        assertThat(logins.get()).as("connexion initiale + un renouvellement").isEqualTo(2);
        assertThat(secureCalls.get()).as("4 requetes + 1 rejeu").isEqualTo(5);
    }

    @Test
    void renewedToken_alsoReplacesAuthTokenVariable_inStepTemplates() throws IOException {
        AtomicInteger logins = new AtomicInteger();
        AtomicInteger firstTokenUses = new AtomicInteger();
        List<String> seen = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        String base = startServer(ex -> {
            if (isLogin(ex)) {
                respond(ex, 200, "{\"data\":{\"token\":\"t-" + logins.incrementAndGet() + "\"}}");
                return;
            }
            String t = ex.getRequestURI().getQuery();
            seen.add(t);
            boolean ok = "t=t-2".equals(t) || ("t=t-1".equals(t) && firstTokenUses.incrementAndGet() <= 1);
            respond(ex, ok ? 200 : 401, "");
        });

        // L'etape envoie aussi l'en-tete de l'application (pas d'en-tete propre) et utilise ${authToken}.
        ScenarioExecutionOutcome outcome = run(List.of(get("/echo?t=${authToken}", null, null)), base, loginToken(), 1, 2,
                new RunningExecutionHandle());

        assertThat(outcome.stepOutcomes()).hasSize(2).allMatch(StepOutcome::success);
        assertThat(seen).containsExactly("t=t-1", "t=t-1", "t=t-2");
    }

    // ------------------------------------------------------------------ echec de la reconnexion

    @Test
    void renewalFails_noInfiniteLoop_failureReportedWithReason() throws IOException {
        AtomicInteger logins = new AtomicInteger();
        AtomicInteger secureCalls = new AtomicInteger();
        String base = startServer(ex -> {
            if (isLogin(ex)) {
                // Premiere connexion acceptee, toutes les suivantes refusees.
                if (logins.incrementAndGet() == 1) respond(ex, 200, "{\"data\":{\"token\":\"t-1\"}}");
                else respond(ex, 500, "{\"error\":\"down\"}");
                return;
            }
            // Le jeton t-1 n'est accepte qu'une fois.
            respond(ex, secureCalls.incrementAndGet() == 1 ? 200 : 401, "");
        });

        ScenarioExecutionOutcome outcome = run(List.of(get("/secure", null, null)), base, loginToken(), 1, 3,
                new RunningExecutionHandle());

        assertThat(outcome.stepOutcomes()).hasSize(3);
        assertThat(outcome.stepOutcomes().get(0).success()).isTrue();
        assertThat(outcome.stepOutcomes().subList(1, 3)).allSatisfy(o -> {
            assertThat(o.success()).isFalse();
            assertThat(o.httpStatus()).isEqualTo(401);
            assertThat(o.error()).endsWith("reconnexion a l'application impossible");
        });
        assertThat(secureCalls.get()).as("aucun rejeu sans nouveau jeton").isEqualTo(3);
        assertThat(logins.get()).as("une seule tentative de reconnexion (pause de 5 s ensuite)").isEqualTo(2);
    }

    @Test
    void cooldown_afterFailedRenewal_thenNewAttemptAllowed() throws IOException {
        AtomicInteger logins = new AtomicInteger();
        String base = startServer(ex -> respond(ex, isLogin(ex) && logins.incrementAndGet() == 1 ? 200 : 500,
                isLogin(ex) && logins.get() == 1 ? "{\"data\":{\"token\":\"t-1\"}}" : "{}"));
        ApplicationAuthenticator authenticator = new ApplicationAuthenticator(new ObjectMapper());
        ResolvedAuth initial = authenticator.authenticate(loginToken(), base, 2);
        AtomicLong now = new AtomicLong(0);
        RenewableApplicationAuth shared = new RenewableApplicationAuth(loginToken(), initial, base, 2, authenticator, null, now::get);
        RenewableApplicationAuth.Current first = shared.current();

        assertThat(shared.renewAfter(first)).isNull();
        assertThat(shared.renewAfter(first)).as("pendant la pause : aucune nouvelle connexion").isNull();
        assertThat(logins.get()).isEqualTo(2);
        now.addAndGet(RenewableApplicationAuth.RETRY_COOLDOWN_NANOS);
        assertThat(shared.renewAfter(first)).isNull();
        assertThat(logins.get()).as("apres la pause : nouvelle tentative").isEqualTo(3);
    }

    // ------------------------------------------------------------------ concurrence

    @Test
    void manyVirtualUsers_simultaneous401_singleRenewal_allReplaysSucceed() throws IOException, InterruptedException {
        AtomicInteger logins = new AtomicInteger();
        int vus = 20;
        CountDownLatch all401 = new CountDownLatch(vus);
        String base = startServer(ex -> {
            if (isLogin(ex)) {
                respond(ex, 200, "{\"data\":{\"token\":\"t-" + logins.incrementAndGet() + "\"}}");
                return;
            }
            if ("Bearer t-1".equals(bearer(ex))) {
                // Jeton deja expire : on attend que les 20 VUs l'aient tous envoye, pour des 401 simultanes.
                all401.countDown();
                try {
                    all401.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                respond(ex, 401, "");
            } else {
                respond(ex, "Bearer t-2".equals(bearer(ex)) ? 200 : 401, "");
            }
        });

        ScenarioExecutionOutcome outcome = run(List.of(get("/secure", null, null)), base, loginToken(), vus, 1,
                new RunningExecutionHandle());

        assertThat(all401.getCount()).as("les 20 premiers envois ont bien eu lieu avec l'ancien jeton").isZero();
        assertThat(logins.get()).as("connexion initiale + UN seul renouvellement pour les 20 VUs").isEqualTo(2);
        assertThat(outcome.stepOutcomes()).hasSize(vus).allMatch(StepOutcome::success);
        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.SUCCESS);
    }

    // ------------------------------------------------------------------ cas non concernes

    @Test
    void stepWithOwnAuthorizationHeader_401_noRenewal() throws IOException {
        AtomicInteger logins = new AtomicInteger();
        AtomicInteger secureCalls = new AtomicInteger();
        String base = startServer(ex -> {
            if (isLogin(ex)) {
                logins.incrementAndGet();
                respond(ex, 200, "{\"data\":{\"token\":\"t-1\"}}");
            } else {
                secureCalls.incrementAndGet();
                respond(ex, 401, "");
            }
        });

        // Jeton propre a l'etape (ex. capture ${userToken}) : un 401 ne concerne pas l'application.
        ScenarioExecutionOutcome outcome = run(List.of(get("/secure", "{\"Authorization\":\"Bearer mine\"}", null)), base,
                loginToken(), 1, 1, new RunningExecutionHandle());

        assertThat(outcome.stepOutcomes()).singleElement().satisfies(o -> {
            assertThat(o.success()).isFalse();
            assertThat(o.error()).doesNotContain("reconnexion");
        });
        assertThat(logins.get()).isEqualTo(1);
        assertThat(secureCalls.get()).isEqualTo(1);
    }

    @Test
    void expected401_isASuccess_noRenewal() throws IOException {
        AtomicInteger logins = new AtomicInteger();
        String base = startServer(ex -> {
            if (isLogin(ex)) {
                logins.incrementAndGet();
                respond(ex, 200, "{\"data\":{\"token\":\"t-1\"}}");
            } else {
                respond(ex, 401, "");
            }
        });

        ScenarioExecutionOutcome outcome = run(List.of(get("/secure", null, 401)), base, loginToken(), 1, 2,
                new RunningExecutionHandle());

        assertThat(outcome.stepOutcomes()).hasSize(2).allMatch(StepOutcome::success);
        assertThat(logins.get()).isEqualTo(1);
    }

    @Test
    void otherAuthModes_401_unchanged_noReplay() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        String base = startServer(ex -> {
            calls.incrementAndGet();
            respond(ex, 401, "");
        });
        ApplicationAuthSpec bearer = new ApplicationAuthSpec(ApplicationAuthType.BEARER_TOKEN,
                new ApplicationAuthConfig("fixed", null, null, null, null, null, null, null, null, null, null));

        ScenarioExecutionOutcome outcome = run(List.of(get("/secure", null, null)), base, bearer, 1, 1,
                new RunningExecutionHandle());

        assertThat(calls.get()).as("un seul envoi, aucun rejeu").isEqualTo(1);
        assertThat(outcome.stepOutcomes()).singleElement().satisfies(o -> {
            assertThat(o.httpStatus()).isEqualTo(401);
            assertThat(o.error()).doesNotContain("reconnexion");
        });
    }

    // ------------------------------------------------------------------ secrets

    @Test
    void renewedToken_isMaskedInRequestDetails() throws IOException {
        AtomicInteger logins = new AtomicInteger();
        AtomicInteger firstTokenUses = new AtomicInteger();
        String base = startServer(ex -> {
            if (isLogin(ex)) {
                respond(ex, 200, "{\"data\":{\"token\":\"t-" + logins.incrementAndGet() + "\"}}");
                return;
            }
            boolean ok = "Bearer t-2".equals(bearer(ex)) || ("Bearer t-1".equals(bearer(ex)) && firstTokenUses.incrementAndGet() <= 1);
            respond(ex, ok ? 200 : 401, "");
        });
        RunningExecutionHandle handle = new RunningExecutionHandle();
        RequestDetailRecorder recorder = new RequestDetailRecorder(RequestDetailMode.FAILURES_AND_SAMPLE, 10_000_000);
        handle.detailRecorder(recorder);

        run(List.of(get("/secure", null, null)), base, loginToken(), 1, 2, handle);

        assertThat(logins.get()).isEqualTo(2);
        assertThat(recorder.baseSecrets()).contains("t-1", "t-2");
    }
}
