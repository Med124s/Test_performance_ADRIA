package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.enums.CaptureSource;
import com.loadpilot.backend.enums.CsvDistribution;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.enums.HttpMethod;
import com.loadpilot.backend.service.auth.ApplicationAuthSpec;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Cookies de session geres automatiquement par utilisateur virtuel : renvoi au meme VU, isolation
 * entre VUs, session vierge a chaque iteration, coexistence avec un en-tete Cookie ecrit dans une
 * etape et avec la capture manuelle Set-Cookie:NOM, aucune retention memoire des pots de cookies.
 */
class EngineCookiesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static HttpServer server;
    private static String baseUrl;
    /** En-tetes Cookie recus par /echo, dans l'ordre. */
    private static final List<String> received = Collections.synchronizedList(new ArrayList<>());

    private final HttpClientExecutionEngine engine = new HttpClientExecutionEngine(5, MAPPER, EXECUTOR);

    @BeforeAll
    static void start() throws IOException {
        // File d'attente de connexions large : jusqu'a 300 VUs se connectent presque en meme temps.
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 1024);
        server.createContext("/", EngineCookiesTest::handle);
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

    @BeforeEach
    void reset() {
        received.clear();
    }

    private static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String vu = ex.getRequestHeaders().getFirst("X-VU");
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        int status = 200;
        switch (path) {
            // Connexion : session propre au VU qui se connecte.
            case "/login" -> ex.getResponseHeaders().add("Set-Cookie", "SID=session-" + vu + "; Path=/; HttpOnly");
            // Page protegee : exige la session de CE VU, jamais celle d'un autre.
            case "/account" -> status = ("SID=session-" + vu).equals(cookie) ? 200 : 401;
            // Debut d'iteration : aucune session ne doit subsister de l'iteration precedente.
            case "/fresh" -> status = cookie == null ? 200 : 409;
            case "/echo" -> received.add(String.valueOf(cookie));
            default -> status = 404;
        }
        ex.sendResponseHeaders(status, -1);
        ex.close();
    }

    private static StepExecutionSpec get(String name, String url, String headers) {
        return new StepExecutionSpec(UUID.randomUUID(), name, HttpMethod.GET, url, headers, null, null, null, null, null, null,
                null, null);
    }

    private static List<Map<String, String>> vuRows(int count) {
        return IntStream.range(0, count).mapToObj(i -> Map.of("vu", "vu" + i)).toList();
    }

    private ScenarioExecutionOutcome run(HttpClientExecutionEngine engine, List<StepExecutionSpec> steps, int vus, int iterations) {
        return run(engine, steps, vus, iterations, 0);
    }

    private ScenarioExecutionOutcome run(HttpClientExecutionEngine engine, List<StepExecutionSpec> steps, int vus, int iterations,
            int rampUpSeconds) {
        return engine.execute(steps, baseUrl, ApplicationAuthSpec.none(), new LoadTestSpec(vus, rampUpSeconds, null, iterations, 0, null),
                new RunningExecutionHandle(), new VariableSources(vuRows(vus), CsvDistribution.PER_VIRTUAL_USER, Map.of(), steps));
    }

    @Test
    void cookieSetByStep1_isSentAutomaticallyByStep2_ofTheSameVirtualUser() {
        List<StepExecutionSpec> steps = List.of(
                get("login", "/login", "{\"X-VU\":\"${vu}\"}"),
                get("account", "/account", "{\"X-VU\":\"${vu}\"}"));

        ScenarioExecutionOutcome outcome = run(engine, steps, 1, 1);

        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(outcome.stepOutcomes()).extracting(StepOutcome::httpStatus).containsExactly(200, 200);
    }

    @Test
    void virtualUsers_neverShareTheirCookies() {
        // 30 VUs simultanes : /account renvoie 401 si un VU recoit la session d'un autre (ou aucune).
        List<StepExecutionSpec> steps = List.of(
                get("login", "/login", "{\"X-VU\":\"${vu}\"}"),
                get("account", "/account", "{\"X-VU\":\"${vu}\"}"));

        ScenarioExecutionOutcome outcome = run(engine, steps, 30, 2);

        assertThat(outcome.stepOutcomes()).hasSize(120).allSatisfy(o -> assertThat(o.httpStatus()).isEqualTo(200));
        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.SUCCESS);
    }

    @Test
    void eachIteration_startsWithoutTheCookiesOfThePreviousOne() {
        List<StepExecutionSpec> steps = List.of(
                get("fresh", "/fresh", null),
                get("login", "/login", "{\"X-VU\":\"${vu}\"}"),
                get("account", "/account", "{\"X-VU\":\"${vu}\"}"));

        ScenarioExecutionOutcome outcome = run(engine, steps, 2, 3);

        // Iterations 2 et 3 : /fresh renverrait 409 si la session de l'iteration precedente etait renvoyee.
        assertThat(outcome.stepOutcomes()).hasSize(18).allSatisfy(o -> assertThat(o.httpStatus()).isEqualTo(200));
    }

    @Test
    void cookieHeaderWrittenInAStep_isKept_automaticCookiesAreAdded_andTheStepWinsForTheSameName() {
        List<StepExecutionSpec> steps = List.of(
                get("login", "/login", "{\"X-VU\":\"A\"}"),
                get("echo-auto", "/echo", null),
                get("echo-extra", "/echo", "{\"Cookie\":\"lang=fr\"}"),
                get("echo-override", "/echo", "{\"Cookie\":\"SID=forced\"}"));

        ScenarioExecutionOutcome outcome = run(engine, steps, 1, 1);

        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(received).containsExactly(
                "SID=session-A",
                "SID=session-A; lang=fr",
                // Jamais "SID=session-A; SID=forced" : la valeur ecrite dans l'etape l'emporte.
                "SID=forced");
    }

    @Test
    void manualSetCookieCapture_stillWorks_alongsideAutomaticCookies() {
        List<StepExecutionSpec> steps = List.of(
                new StepExecutionSpec(UUID.randomUUID(), "login", HttpMethod.GET, "/login", "{\"X-VU\":\"B\"}", null, null, null,
                        null, null, null, "sid", "Set-Cookie:SID", null, null, CaptureSource.HEADER),
                // Variable visible reutilisee ailleurs que dans Cookie, pendant que le cookie part tout seul.
                get("echo", "/echo?session=${sid}", null));

        ScenarioExecutionOutcome outcome = run(engine, steps, 1, 1);

        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(outcome.stepOutcomes().get(1).requestUrl()).endsWith("/echo?session=session-B");
        assertThat(received).containsExactly("SID=session-B");
    }

    @Test
    void cookieJars_areNotRetainedAfterATestWithHundredsOfVirtualUsers() throws InterruptedException {
        List<WeakReference<VirtualUserCookieJar>> jars = Collections.synchronizedList(new ArrayList<>());
        HttpClientExecutionEngine tracking = new HttpClientExecutionEngine(5, MAPPER, EXECUTOR) {
            @Override
            VirtualUserCookieJar newCookieJar() {
                VirtualUserCookieJar jar = super.newCookieJar();
                jars.add(new WeakReference<>(jar));
                return jar;
            }
        };
        List<StepExecutionSpec> steps = List.of(
                get("login", "/login", "{\"X-VU\":\"${vu}\"}"),
                get("account", "/account", "{\"X-VU\":\"${vu}\"}"));

        // Montee en charge de 2 s : 300 VUs sans saturer le petit serveur HTTP local du test.
        ScenarioExecutionOutcome outcome = run(tracking, steps, 300, 1, 2);
        assertThat(outcome.finalStatus()).as("premiere erreur : %s", outcome.errorMessage()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(jars).hasSize(300);

        // Une fois l'execution terminee, plus rien ne reference les pots (ni le moteur ni les VUs) :
        // le ramasse-miettes les libere. Les clients HTTP du JDK liberent leurs ressources en
        // quelques secondes apres leur dernier usage, d'ou l'attente bornee.
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (jars.stream().anyMatch(ref -> ref.get() != null) && System.nanoTime() < deadline) {
            System.gc();
            Thread.sleep(200);
        }
        assertThat(jars.stream().filter(ref -> ref.get() != null).count()).as("pots de cookies encore en memoire").isZero();
    }
}
