package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.enums.CsvDistribution;
import com.loadpilot.backend.enums.HttpMethod;
import com.loadpilot.backend.service.auth.ApplicationAuthSpec;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Ralentissement sur 429 dans le moteur : attente du delai demande (Retry-After en secondes, en
 * date HTTP, ou delai par defaut), rejeu de la meme etape, limite de rejeux, desactivation, attente
 * interrompue par une annulation ou la fin du test, et 429 exclus de la fenetre du frein.
 */
class EngineThrottleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static HttpServer server;
    private static String baseUrl;
    /** Appels recus par cle (?key=...) et heure de chaque appel (ms). */
    private static final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private static final List<Long> times = Collections.synchronizedList(new ArrayList<>());

    private final HttpClientExecutionEngine engine = new HttpClientExecutionEngine(5, MAPPER, EXECUTOR);

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", EngineThrottleTest::handle);
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
        calls.clear();
        times.clear();
    }

    /**
     * /limited?key=K&mode=M : premier appel en 429 (Retry-After selon M : seconds = « 1 », date =
     * date HTTP dans 2 s, none = aucun en-tete), puis 200. /always?retry=V : toujours 429. /ok, /fail.
     */
    private static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        int status = 200;
        switch (path) {
            case "/limited" -> {
                times.add(System.currentTimeMillis());
                if (calls.computeIfAbsent(q.get("key"), k -> new AtomicInteger()).incrementAndGet() == 1) {
                    status = 429;
                    switch (q.getOrDefault("mode", "none")) {
                        case "seconds" -> ex.getResponseHeaders().add("Retry-After", "1");
                        case "date" -> ex.getResponseHeaders().add("Retry-After",
                                DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(2)));
                        default -> { }
                    }
                }
            }
            case "/always" -> {
                times.add(System.currentTimeMillis());
                status = 429;
                if (q.containsKey("retry")) ex.getResponseHeaders().add("Retry-After", q.get("retry"));
            }
            case "/fail" -> status = 500;
            case "/ok" -> status = 200;
            default -> status = 404;
        }
        ex.sendResponseHeaders(status, -1);
        ex.close();
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new java.util.HashMap<>();
        if (raw == null) return m;
        for (String part : raw.split("&")) {
            String[] kv = part.split("=", 2);
            m.put(kv[0], kv.length > 1 ? kv[1] : "");
        }
        return m;
    }

    private static StepExecutionSpec get(String url) {
        return new StepExecutionSpec(UUID.randomUUID(), url, HttpMethod.GET, url, null, null, null, null, null, null, null, null, null);
    }

    private ScenarioExecutionOutcome run(List<StepExecutionSpec> steps, ThrottlePolicy policy, Integer durationSeconds,
            RunningExecutionHandle handle) {
        return engine.execute(steps, baseUrl, ApplicationAuthSpec.none(),
                new LoadTestSpec(1, 0, durationSeconds, durationSeconds == null ? 1 : null, 0, null, policy), handle,
                new VariableSources(List.of(), CsvDistribution.PER_VIRTUAL_USER, Map.of(), steps));
    }

    private ScenarioExecutionOutcome run(List<StepExecutionSpec> steps, ThrottlePolicy policy) {
        return run(steps, policy, null, new RunningExecutionHandle());
    }

    private static List<Integer> statuses(ScenarioExecutionOutcome outcome) {
        return outcome.stepOutcomes().stream().map(StepOutcome::httpStatus).toList();
    }

    private static long gapMs() {
        return times.get(1) - times.get(0);
    }

    @Test
    void retryAfterInSeconds_waitsThenReplaysTheSameStep_andTheIterationGoesOn() {
        ScenarioExecutionOutcome outcome = run(List.of(get("/limited?key=s&mode=seconds"), get("/ok")), ThrottlePolicy.DEFAULT);

        // 429, puis rejeu de la meme etape (200), puis l'etape suivante : l'iteration n'est pas interrompue.
        assertThat(statuses(outcome)).containsExactly(429, 200, 200);
        assertThat(gapMs()).as("Retry-After: 1").isBetween(900L, 3000L);
        assertThat(outcome.stepOutcomes().get(0).success()).as("le 429 reste un echec dans les resultats").isFalse();
    }

    @Test
    void retryAfterAsHttpDate_waitsUntilThatDate() {
        ScenarioExecutionOutcome outcome = run(List.of(get("/limited?key=d&mode=date")), ThrottlePolicy.DEFAULT);

        assertThat(statuses(outcome)).containsExactly(429, 200);
        // Date HTTP a la seconde pres, 2 s plus tard : entre 1 et 2 s d'attente.
        assertThat(gapMs()).isBetween(900L, 3500L);
    }

    @Test
    void withoutRetryAfter_theDefaultDelayApplies() {
        ScenarioExecutionOutcome outcome = run(List.of(get("/limited?key=n&mode=none")), ThrottlePolicy.of(true, 1, 3));

        assertThat(statuses(outcome)).containsExactly(429, 200);
        assertThat(gapMs()).as("delai par defaut regle a 1 s").isBetween(900L, 3000L);
    }

    @Test
    void disabled_a429IsAnOrdinaryFailure_noWaitNoReplay() {
        long started = System.currentTimeMillis();
        ScenarioExecutionOutcome outcome = run(List.of(get("/always?retry=5"), get("/ok")), ThrottlePolicy.DISABLED);

        assertThat(statuses(outcome)).as("iteration interrompue au premier echec, comme avant").containsExactly(429);
        assertThat(System.currentTimeMillis() - started).as("aucune attente malgre Retry-After: 5").isLessThan(3000L);
    }

    @Test
    void replaysAreLimited_thenTheStepFailsAsUsual() {
        ScenarioExecutionOutcome outcome = run(List.of(get("/always?retry=0"), get("/ok")), ThrottlePolicy.of(true, 1, 2));

        // 1 envoi + 2 rejeux, tous en 429 ; l'etape suivante n'est pas jouee.
        assertThat(statuses(outcome)).containsExactly(429, 429, 429);
    }

    @Test
    void theWait_isInterruptedByACancellation_andNeverOutlivesTheTestDuration() throws Exception {
        // Annulation pendant un Retry-After de 300 s.
        RunningExecutionHandle handle = new RunningExecutionHandle();
        Thread canceller = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            handle.requestCancellation();
        });
        long started = System.currentTimeMillis();
        run(List.of(get("/always?retry=300")), ThrottlePolicy.DEFAULT, null, handle);
        canceller.join();
        assertThat(System.currentTimeMillis() - started).isLessThan(5000L);

        // Test de 2 s : l'attente s'arrete a la fin prevue, meme si la cible demande 300 s.
        times.clear();
        started = System.currentTimeMillis();
        ScenarioExecutionOutcome outcome = run(List.of(get("/always?retry=300")), ThrottlePolicy.DEFAULT, 2, new RunningExecutionHandle());
        assertThat(System.currentTimeMillis() - started).isBetween(1500L, 6000L);
        assertThat(statuses(outcome)).containsExactly(429);
    }

    @Test
    void respected429s_areLeftOutOfTheBrakeWindow_butRealErrorsStillCount() {
        ErrorRateBrake.Config brake = new ErrorRateBrake.Config(10.0, 30);

        // Ralentissement actif : 25 x 429 respectes -> ni erreur, ni requete pour le frein.
        RunningExecutionHandle respected = new RunningExecutionHandle();
        // (25 iterations sans rejeu : le nombre de rejeux est borne a 10.)
        List<StepExecutionSpec> limited = List.of(get("/always?retry=0"));
        engine.execute(limited, baseUrl, ApplicationAuthSpec.none(),
                new LoadTestSpec(1, 0, null, 25, 0, null, ThrottlePolicy.of(true, 1, 0)), respected,
                new VariableSources(List.of(), CsvDistribution.PER_VIRTUAL_USER, Map.of(), limited));
        LiveStats.Window window = respected.liveStats().recentWindow(30);
        assertThat(window).isEqualTo(new LiveStats.Window(0, 0, 25));
        assertThat(ErrorRateBrake.evaluate(brake, window)).isNull();
        // Le suivi en direct (et le resultat final) les compte bien comme des echecs.
        assertThat(respected.liveStats().snapshot().failures()).isEqualTo(25);

        // Ralentissement desactive : chaque 429 est un echec ordinaire, que le frein juge.
        RunningExecutionHandle disabled = new RunningExecutionHandle();
        List<StepExecutionSpec> always = List.of(get("/always"));
        engine.execute(always, baseUrl, ApplicationAuthSpec.none(),
                new LoadTestSpec(1, 0, null, 25, 0, null, ThrottlePolicy.DISABLED), disabled,
                new VariableSources(List.of(), CsvDistribution.PER_VIRTUAL_USER, Map.of(), always));
        assertThat(disabled.liveStats().recentWindow(30)).isEqualTo(new LiveStats.Window(25, 25, 0));
        assertThat(ErrorRateBrake.evaluate(brake, disabled.liveStats().recentWindow(30))).startsWith(ErrorRateBrake.REASON_PREFIX);

        // Ralentissement actif mais vraies erreurs (500) : toujours jugees par le frein.
        RunningExecutionHandle realErrors = new RunningExecutionHandle();
        List<StepExecutionSpec> fail = List.of(get("/fail"));
        engine.execute(fail, baseUrl, ApplicationAuthSpec.none(),
                new LoadTestSpec(1, 0, null, 25, 0, null, ThrottlePolicy.DEFAULT), realErrors,
                new VariableSources(List.of(), CsvDistribution.PER_VIRTUAL_USER, Map.of(), fail));
        assertThat(realErrors.liveStats().recentWindow(30)).isEqualTo(new LiveStats.Window(25, 25, 0));
    }
}
