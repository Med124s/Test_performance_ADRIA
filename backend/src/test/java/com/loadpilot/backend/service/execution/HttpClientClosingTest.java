package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.dto.response.TestRunResponse;
import com.loadpilot.backend.enums.ApplicationAuthType;
import com.loadpilot.backend.enums.CsvDistribution;
import com.loadpilot.backend.enums.HttpMethod;
import com.loadpilot.backend.service.auth.ApplicationAuthConfig;
import com.loadpilot.backend.service.auth.ApplicationAuthSpec;
import com.loadpilot.backend.service.http.JavaHttpClientAvailabilityChecker;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Clients HTTP fermes tout de suite : chaque java.net.http.HttpClient demarre un thread systeme
 * « HttpClient-N-SelectorManager », qui sinon reste vivant (avec ses descripteurs et connexions)
 * jusqu'au passage du GC. Verifie : aucun thread de selecteur cree par un appel ne subsiste apres
 * lui (moteur en fin normale, en erreur et a l'annulation ; connexion automatique ; test de
 * disponibilite ; test de validation), et le second client du moteur n'est cree qu'a la demande.
 */
class HttpClientClosingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static HttpServer server;
    private static String baseUrl;
    /** /hold bloque jusqu'a ce que le test le libere (compter les clients pendant le test). */
    private static volatile CountDownLatch hold = new CountDownLatch(0);
    private static final CountDownLatch holding = new CountDownLatch(1);

    private final HttpClientExecutionEngine engine = new HttpClientExecutionEngine(5, MAPPER, EXECUTOR);
    private Set<String> before;

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", HttpClientClosingTest::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
        EXECUTOR.shutdown();
    }

    private static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/hold")) {
            try {
                holding.countDown();
                hold.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] body = "{\"data\":{\"token\":\"t-1\"}}".getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(path.equals("/fail") ? 500 : 200, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    private static Set<String> selectorThreads() {
        return Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive).map(Thread::getName)
                .filter(n -> n.startsWith("HttpClient-") && n.endsWith("-SelectorManager")).collect(Collectors.toSet());
    }

    /** Threads de selecteur crees depuis le debut du test et encore vivants. */
    private Set<String> created() {
        Set<String> now = new HashSet<>(selectorThreads());
        now.removeAll(before);
        return now;
    }

    /** Attend au plus 500 ms que les threads de selecteur crees par le test aient disparu. */
    private Set<String> leftoversAfter500ms() throws InterruptedException {
        long until = System.currentTimeMillis() + 500;
        Set<String> left = created();
        while (!left.isEmpty() && System.currentTimeMillis() < until) {
            Thread.sleep(20);
            left = created();
        }
        return left;
    }

    @BeforeEach
    void snapshot() {
        before = selectorThreads();
    }

    private static StepExecutionSpec get(String url, Boolean followRedirects) {
        return new StepExecutionSpec(UUID.randomUUID(), url, HttpMethod.GET, url, null, null, null, null, null, followRedirects,
                null, null, null);
    }

    private ScenarioExecutionOutcome run(List<StepExecutionSpec> steps, int vus, RunningExecutionHandle handle) {
        return engine.execute(steps, baseUrl, ApplicationAuthSpec.none(), new LoadTestSpec(vus, 0, null, 2, 0, null), handle,
                new VariableSources(List.of(), CsvDistribution.PER_VIRTUAL_USER, Map.of(), steps));
    }

    @Test
    void engine_normalEnd_noSelectorThreadLeft() throws Exception {
        ScenarioExecutionOutcome outcome = run(List.of(get("/ok", null), get("/ok", false)), 5, new RunningExecutionHandle());
        assertThat(outcome.stepOutcomes()).hasSize(20).allSatisfy(o -> assertThat(o.httpStatus()).isEqualTo(200));
        assertThat(leftoversAfter500ms()).isEmpty();
    }

    @Test
    void engine_errorsAndUnreachableTarget_noSelectorThreadLeft() throws Exception {
        assertThat(run(List.of(get("/fail", null)), 3, new RunningExecutionHandle()).stepOutcomes())
                .allSatisfy(o -> assertThat(o.httpStatus()).isEqualTo(500));
        List<StepExecutionSpec> steps = List.of(get("/ok", null));
        ScenarioExecutionOutcome unreachable = engine.execute(steps, "http://localhost:1", ApplicationAuthSpec.none(),
                new LoadTestSpec(3, 0, null, 1, 0, null), new RunningExecutionHandle(),
                new VariableSources(List.of(), CsvDistribution.PER_VIRTUAL_USER, Map.of(), steps));
        assertThat(unreachable.stepOutcomes()).isNotEmpty().allSatisfy(o -> assertThat(o.httpStatus()).isNull());
        assertThat(leftoversAfter500ms()).isEmpty();
    }

    @Test
    void engine_cancelledWithRequestsInFlight_noSelectorThreadLeft_andSecondClientOnlyOnDemand() throws Exception {
        hold = new CountDownLatch(1);
        try {
            RunningExecutionHandle handle = new RunningExecutionHandle();
            Thread runner = Thread.ofVirtual().start(() -> run(List.of(get("/hold", null)), 4, handle));
            assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(200);
            // Aucune etape sans redirection : un seul client par VU, pas de second client.
            assertThat(created()).as("un thread de selecteur par VU").hasSize(4);

            handle.requestCancellation(); // en plein appel : les 4 requetes sont en vol
            runner.join(10_000);
            assertThat(runner.isAlive()).isFalse();
            assertThat(leftoversAfter500ms()).isEmpty();
        } finally {
            hold.countDown();
        }
    }

    @Test
    void engine_secondClient_isCreatedWhenAStepDoesNotFollowRedirects() throws Exception {
        hold = new CountDownLatch(1);
        try {
            RunningExecutionHandle handle = new RunningExecutionHandle();
            // Etape 1 sans redirection (second client), etape 2 bloquee (le premier client sert aussi).
            Thread runner = Thread.ofVirtual().start(() -> run(List.of(get("/ok", false), get("/hold", null)), 3, handle));
            long until = System.currentTimeMillis() + 5000;
            while (created().size() < 6 && System.currentTimeMillis() < until) Thread.sleep(20);
            assertThat(created()).as("deux clients par VU").hasSize(6);
            handle.requestCancellation();
            runner.join(10_000);
            assertThat(leftoversAfter500ms()).isEmpty();
        } finally {
            hold.countDown();
        }
    }

    @Test
    void applicationAuthenticator_login_successAndFailure_noSelectorThreadLeft() throws Exception {
        ApplicationAuthenticator authenticator = new ApplicationAuthenticator(MAPPER);
        ApplicationAuthSpec login = new ApplicationAuthSpec(ApplicationAuthType.LOGIN_TOKEN,
                new ApplicationAuthConfig(null, null, null, null, null, null, "/login", "{}", "$.data.token", null, null));
        assertThat(authenticator.authenticate(login, baseUrl, 5).token()).isEqualTo("t-1");
        ApplicationAuthSpec refused = new ApplicationAuthSpec(ApplicationAuthType.LOGIN_TOKEN,
                new ApplicationAuthConfig(null, null, null, null, null, null, "/fail", "{}", "$.data.token", null, null));
        try {
            authenticator.authenticate(refused, baseUrl, 5);
        } catch (ApplicationAuthenticationException expected) {
            // login refuse (500) : le client doit etre ferme quand meme
        }
        try {
            authenticator.authenticate(login, "http://localhost:1", 2);
        } catch (ApplicationAuthenticationException expected) {
            // application injoignable
        }
        assertThat(leftoversAfter500ms()).isEmpty();
    }

    @Test
    void availabilityChecker_successAndFailure_noSelectorThreadLeft() throws Exception {
        JavaHttpClientAvailabilityChecker checker = new JavaHttpClientAvailabilityChecker();
        assertThat(checker.check(baseUrl + "/ok", Duration.ofSeconds(5)).success()).isTrue();
        assertThat(checker.check("http://localhost:1/ok", Duration.ofSeconds(2)).success()).isFalse();
        assertThat(leftoversAfter500ms()).isEmpty();
    }

    @Test
    void stepTestRunner_noSelectorThreadLeft() throws Exception {
        StepTestRunner runner = new StepTestRunner(10, 30, MAPPER);
        TestRunResponse ok = runner.run(new TestRunPlan(UUID.randomUUID(), baseUrl, ApplicationAuthSpec.none(),
                List.of(get("/ok", null), get("/ok", false)), Map.of()), StepTestRunner.Listener.NONE);
        assertThat(ok.success()).isTrue();
        TestRunResponse failed = runner.run(new TestRunPlan(UUID.randomUUID(), baseUrl, ApplicationAuthSpec.none(),
                List.of(get("/fail", null)), Map.of()), StepTestRunner.Listener.NONE);
        assertThat(failed.success()).isFalse();
        assertThat(leftoversAfter500ms()).isEmpty();
    }
}
