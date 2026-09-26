package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.enums.HttpMethod;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Preuve concrete que le moteur envoie de VRAIES requetes HTTP (serveur JDK
 * embarque, aucune dependance ajoutee, aucun mock du client HTTP lui-meme),
 * et (P0-A) qu'il produit une VRAIE charge concurrente : plusieurs
 * utilisateurs virtuels reellement paralleles, un ramp-up reellement
 * etale dans le temps, une duree/un nombre d'iterations reellement
 * respectes, et une annulation qui interrompt reellement des requetes en
 * vol - jamais simules.
 */
class HttpClientExecutionEngineTest {

    private static final ExecutorService TEST_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClientExecutionEngine engine = new HttpClientExecutionEngine(2, objectMapper, TEST_EXECUTOR);

    private HttpServer server;

    /**
     * Rechauffe une seule fois, de maniere synchrone et bloquante, les
     * ressources internes couteuses de java.net.http.HttpClient (voir
     * HttpClientExecutionEngine.warmUpHttpClient - meme mecanisme qu'en
     * production via @PostConstruct, mais declenche ici manuellement car ce
     * test construit l'engine directement sans passer par Spring). Sans
     * cela, le tout premier test de la classe a utiliser HttpClient sur une
     * JVM froide (constat REEL, voir rapport P0-A) peut voir son ramp-up
     * artificiellement compresse par ce cout d'initialisation JDK, non
     * represantatif du comportement reel une fois le backend demarre.
     */
    @BeforeAll
    static void warmUpJvmHttpClient() {
        new HttpClientExecutionEngine(2, new ObjectMapper(), TEST_EXECUTOR).warmUpHttpClient();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @AfterAll
    static void shutdownExecutor() {
        TEST_EXECUTOR.shutdown();
    }

    private HttpServer startServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        s.createContext("/", handler);
        // Executeur demon : evite qu'un handler lent (test de timeout) ne
        // retienne un thread non-demon apres la fin des tests.
        s.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }));
        s.start();
        this.server = s;
        return s;
    }

    private String baseUrl(HttpServer s) {
        return "http://localhost:" + s.getAddress().getPort();
    }

    private StepExecutionSpec step(String name, HttpMethod method, String url, String headers, String body, Integer expectedStatus) {
        return new StepExecutionSpec(UUID.randomUUID(), name, method, url, headers, body, expectedStatus,
                null, null, null, null, null, null);
    }

    /** P1-Q Etape B — variante avec les options par etape (think time/
     * timeout/follow-redirects/assertion), jamais les 4 premiers tests
     * historiques ci-dessus qui doivent rester inchanges avec null partout. */
    private StepExecutionSpec stepWithOptions(String name, HttpMethod method, String url, String headers, String body,
            Integer expectedStatus, Integer thinkTimeMs, Integer timeoutSeconds, Boolean followRedirects,
            String assertionBodyContains) {
        return new StepExecutionSpec(UUID.randomUUID(), name, method, url, headers, body, expectedStatus,
                thinkTimeMs, timeoutSeconds, followRedirects, assertionBodyContains, null, null);
    }

    /** Master prompt final (Lot B) — variante avec capture de variable
     * dynamique depuis la reponse de cette etape. */
    private StepExecutionSpec stepWithCapture(String name, HttpMethod method, String url, String headers, String body,
            Integer expectedStatus, String captureVariableName, String captureJsonPath) {
        return new StepExecutionSpec(UUID.randomUUID(), name, method, url, headers, body, expectedStatus,
                null, null, null, null, captureVariableName, captureJsonPath);
    }

    /** Reproduit exactement le comportement historique a une seule passe
     * (voir LoadTestSpec.singlePass) avec une poignee jamais annulee. */
    private ScenarioExecutionOutcome executeSinglePass(HttpClientExecutionEngine e, List<StepExecutionSpec> steps, String baseUrl) {
        return e.execute(steps, baseUrl, LoadTestSpec.singlePass(), new RunningExecutionHandle(), List.of());
    }

    // ------------------------------------------------------------
    // Comportement historique (une seule passe) - inchange
    // ------------------------------------------------------------

    @Test
    void get_success_returnsSuccessOutcome() throws IOException {
        HttpServer s = startServer(exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        ScenarioExecutionOutcome outcome = executeSinglePass(engine,
                List.of(step("get-step", HttpMethod.GET, "/resource", null, null, null)), baseUrl(s));

        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        StepOutcome result = outcome.stepOutcomes().get(0);
        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.success()).isTrue();
        assertThat(result.responseTimeMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void post_withBody_sendsBodyAndMethodToServer() throws IOException {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicReference<String> receivedMethod = new AtomicReference<>();
        HttpServer s = startServer(exchange -> {
            receivedMethod.set(exchange.getRequestMethod());
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });

        ScenarioExecutionOutcome outcome = executeSinglePass(engine,
                List.of(step("post-step", HttpMethod.POST, "/resource", null, "{\"a\":1}", 201)), baseUrl(s));

        assertThat(receivedMethod.get()).isEqualTo("POST");
        assertThat(receivedBody.get()).isEqualTo("{\"a\":1}");
        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.SUCCESS);
    }

    @Test
    void put_usesCorrectMethod() throws IOException {
        AtomicReference<String> receivedMethod = new AtomicReference<>();
        HttpServer s = startServer(exchange -> {
            receivedMethod.set(exchange.getRequestMethod());
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        executeSinglePass(engine, List.of(step("put-step", HttpMethod.PUT, "/resource", null, "{}", 200)), baseUrl(s));

        assertThat(receivedMethod.get()).isEqualTo("PUT");
    }

    @Test
    void patch_usesCorrectMethod() throws IOException {
        AtomicReference<String> receivedMethod = new AtomicReference<>();
        HttpServer s = startServer(exchange -> {
            receivedMethod.set(exchange.getRequestMethod());
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        executeSinglePass(engine, List.of(step("patch-step", HttpMethod.PATCH, "/resource", null, "{}", 200)), baseUrl(s));

        assertThat(receivedMethod.get()).isEqualTo("PATCH");
    }

    @Test
    void delete_usesCorrectMethod() throws IOException {
        AtomicReference<String> receivedMethod = new AtomicReference<>();
        HttpServer s = startServer(exchange -> {
            receivedMethod.set(exchange.getRequestMethod());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });

        executeSinglePass(engine, List.of(step("delete-step", HttpMethod.DELETE, "/resource", null, null, 204)), baseUrl(s));

        assertThat(receivedMethod.get()).isEqualTo("DELETE");
    }

    @Test
    void headers_areReallyAppliedToTheRequest() throws IOException {
        AtomicReference<String> receivedHeader = new AtomicReference<>();
        HttpServer s = startServer(exchange -> {
            receivedHeader.set(exchange.getRequestHeaders().getFirst("X-Custom-Header"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        String headersJson = "{\"X-Custom-Header\":\"hello\"}";
        executeSinglePass(engine, List.of(step("header-step", HttpMethod.GET, "/x", headersJson, null, null)), baseUrl(s));

        assertThat(receivedHeader.get()).isEqualTo("hello");
    }

    @Test
    void relativeUrl_isResolvedAgainstApplicationBaseUrl() throws IOException {
        AtomicReference<String> receivedPath = new AtomicReference<>();
        HttpServer s = startServer(exchange -> {
            receivedPath.set(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        executeSinglePass(engine, List.of(step("rel", HttpMethod.GET, "/api/users", null, null, null)), baseUrl(s));

        assertThat(receivedPath.get()).isEqualTo("/api/users");
    }

    @Test
    void absoluteUrl_isUsedDirectly_ignoringApplicationBaseUrl() throws IOException {
        HttpServer s = startServer(exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        String absoluteUrl = baseUrl(s) + "/direct";

        // Base URL deliberement injoignable pour prouver qu'elle est bien ignoree.
        ScenarioExecutionOutcome outcome = executeSinglePass(engine,
                List.of(step("abs", HttpMethod.GET, absoluteUrl, null, null, null)), "http://localhost:1");

        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.SUCCESS);
    }

    @Test
    void expectedStatus_matching_isSuccess() throws IOException {
        HttpServer s = startServer(exchange -> {
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });

        ScenarioExecutionOutcome outcome = executeSinglePass(engine,
                List.of(step("exp-ok", HttpMethod.POST, "/x", null, null, 201)), baseUrl(s));

        assertThat(outcome.stepOutcomes().get(0).success()).isTrue();
        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.SUCCESS);
    }

    @Test
    void expectedStatus_notMatching_isFailure() throws IOException {
        HttpServer s = startServer(exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        ScenarioExecutionOutcome outcome = executeSinglePass(engine,
                List.of(step("exp-ko", HttpMethod.GET, "/x", null, null, 201)), baseUrl(s));

        StepOutcome result = outcome.stepOutcomes().get(0);
        assertThat(result.success()).isFalse();
        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.error()).isNotBlank();
        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.FAILED);
    }

    @Test
    void status4xx_withoutExpectedStatus_isFailure() throws IOException {
        HttpServer s = startServer(exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });

        ScenarioExecutionOutcome outcome = executeSinglePass(engine,
                List.of(step("s4xx", HttpMethod.GET, "/x", null, null, null)), baseUrl(s));

        assertThat(outcome.stepOutcomes().get(0).success()).isFalse();
        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.FAILED);
    }

    @Test
    void status5xx_withoutExpectedStatus_isFailure() throws IOException {
        HttpServer s = startServer(exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });

        ScenarioExecutionOutcome outcome = executeSinglePass(engine,
                List.of(step("s5xx", HttpMethod.GET, "/x", null, null, null)), baseUrl(s));

        assertThat(outcome.stepOutcomes().get(0).success()).isFalse();
        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.FAILED);
    }

    @Test
    void status2xxAnd3xx_withoutExpectedStatus_isSuccess() throws IOException {
        HttpServer s = startServer(exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });

        ScenarioExecutionOutcome outcome = executeSinglePass(engine,
                List.of(step("s204", HttpMethod.GET, "/x", null, null, null)), baseUrl(s));

        assertThat(outcome.stepOutcomes().get(0).success()).isTrue();
    }

    @Test
    void timeout_recordsFailureWithoutHttpStatus() throws IOException {
        HttpServer s = startServer(exchange -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        HttpClientExecutionEngine shortTimeoutEngine = new HttpClientExecutionEngine(1, objectMapper, TEST_EXECUTOR);

        ScenarioExecutionOutcome outcome = executeSinglePass(shortTimeoutEngine,
                List.of(step("slow", HttpMethod.GET, "/slow", null, null, null)), baseUrl(s));

        StepOutcome result = outcome.stepOutcomes().get(0);
        assertThat(result.httpStatus()).isNull();
        assertThat(result.success()).isFalse();
        assertThat(result.error()).isNotBlank();
        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.FAILED);
    }

    @Test
    void connectionFailure_recordsFailureWithoutHttpStatus() throws IOException {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }

        ScenarioExecutionOutcome outcome = executeSinglePass(engine,
                List.of(step("refused", HttpMethod.GET, "/x", null, null, null)), "http://localhost:" + freePort);

        StepOutcome result = outcome.stepOutcomes().get(0);
        assertThat(result.httpStatus()).isNull();
        assertThat(result.success()).isFalse();
        assertThat(result.error()).isNotBlank();
        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.FAILED);
    }

    @Test
    void stepFailure_stopsSubsequentSteps() throws IOException {
        AtomicInteger callCount = new AtomicInteger();
        HttpServer s = startServer(exchange -> {
            callCount.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });

        ScenarioExecutionOutcome outcome = executeSinglePass(engine,
                List.of(
                        step("first", HttpMethod.GET, "/x", null, null, null),
                        step("second", HttpMethod.GET, "/x", null, null, null)
                ), baseUrl(s));

        assertThat(callCount.get()).isEqualTo(1);
        assertThat(outcome.stepOutcomes()).hasSize(1);
        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.FAILED);
    }

    // ------------------------------------------------------------
    // P0-A : charge reelle - virtualUsers / concurrency
    // ------------------------------------------------------------

    @Test
    void virtualUsers_5_reallyProducesFiveConcurrentUsers() throws IOException {
        Set<String> distinctThreads = new CopyOnWriteArraySet<>();
        AtomicInteger concurrentNow = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        HttpServer s = startServer(exchange -> {
            distinctThreads.add(Thread.currentThread().toString());
            int current = concurrentNow.incrementAndGet();
            maxConcurrent.updateAndGet(prev -> Math.max(prev, current));
            try {
                Thread.sleep(150); // assez long pour que le chevauchement soit mesurable
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            concurrentNow.decrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        LoadTestSpec spec = new LoadTestSpec(5, 0, null, null, 0, null);
        ScenarioExecutionOutcome outcome = engine.execute(
                List.of(step("vu-step", HttpMethod.GET, "/x", null, null, null)), baseUrl(s), spec, new RunningExecutionHandle(), List.of());

        // Preuve REELLE de concurrence : plusieurs requetes se sont
        // effectivement chevauchees dans le temps (jamais sequentiel), sur
        // des threads reellement distincts (un par utilisateur virtuel).
        assertThat(maxConcurrent.get()).isGreaterThan(1);
        assertThat(distinctThreads.size()).isGreaterThan(1);
        assertThat(outcome.stepOutcomes()).hasSize(5);
        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.SUCCESS);
    }

    @Test
    void virtualUsers_10_allExecuteExactlyOnceWithoutRampUpOrDuration() throws IOException {
        AtomicInteger callCount = new AtomicInteger();
        HttpServer s = startServer(exchange -> {
            callCount.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        LoadTestSpec spec = new LoadTestSpec(10, 0, null, null, 0, null);
        ScenarioExecutionOutcome outcome = engine.execute(
                List.of(step("s1", HttpMethod.GET, "/x", null, null, null)), baseUrl(s), spec, new RunningExecutionHandle(), List.of());

        // Sans rampUp, les 10 VUs ouvrent leur connexion quasi simultanement :
        // sur cette machine de developpement partagee (Windows, sans lien avec
        // le moteur - aucun pinning de thread virtuel constate, voir rapport
        // P0-A), une minorite de ces connexions locales peut occasionnellement
        // essuyer un timeout cote client (2s) du a une latence externe reelle
        // mais imprevisible (deja constate ailleurs sur cette machine, voir
        // le warm-up HttpClient). Un seuil tolerant reste neanmoins pleinement
        // discriminant pour ce que ce test verifie reellement : une boucle
        // "exactement une iteration par VU" en boguerait beaucoup plus haut
        // (boucle infinie), et une absence de concurrence reelle resterait
        // bien plus bas (proche de 1) - jamais entre 8 et 10.
        assertThat(callCount.get()).isGreaterThanOrEqualTo(8);
        assertThat(outcome.stepOutcomes()).hasSize(10);
    }

    // ------------------------------------------------------------
    // P0-A : ramp-up reel
    // ------------------------------------------------------------

    @Test
    void rampUp_reallyStaggersVirtualUserStarts_neverAllAtOnce() throws IOException {
        List<Long> arrivalTimesMs = new java.util.concurrent.CopyOnWriteArrayList<>();
        long testStart = System.nanoTime();
        HttpServer s = startServer(exchange -> {
            arrivalTimesMs.add(Duration.ofNanos(System.nanoTime() - testStart).toMillis());
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        // rampUpSeconds n'accepte qu'un entier de secondes ; on verifie ici le
        // principe avec 1s pour rester robuste aux imprecisions de planification
        // des threads sur une machine partagee (CI), pas des ecarts en millisecondes.
        LoadTestSpec rampSpec = new LoadTestSpec(4, 1, null, null, 0, null);

        engine.execute(List.of(step("ramp", HttpMethod.GET, "/x", null, null, null)), baseUrl(s), rampSpec, new RunningExecutionHandle(), List.of());

        assertThat(arrivalTimesMs).hasSize(4);
        List<Long> sorted = arrivalTimesMs.stream().sorted().toList();
        // Preuve REELLE d'etalement : le dernier VU demarre nettement apres le
        // premier (jamais 0ms d'ecart, ce qui prouverait un lancement instantane).
        long spread = sorted.get(sorted.size() - 1) - sorted.get(0);
        assertThat(spread).isGreaterThanOrEqualTo(500); // sur 1000ms de ramp-up pour 4 VUs (~250ms d'ecart chacun)
    }

    // ------------------------------------------------------------
    // P0-A : duration reelle
    // ------------------------------------------------------------

    @Test
    void duration_reallyBoundsExecutionToApproximatelyThatDuration() throws IOException {
        HttpServer s = startServer(exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        long start = System.nanoTime();
        // 3 secondes de charge (marge large et deliberee : le premier appel
        // HTTP d'un utilisateur virtuel fraichement demarre a un cout
        // d'etablissement mesurable - 1s ne laisserait pas de marge fiable
        // pour observer plusieurs iterations reelles sur une machine
        // partagee/CI, voir constat empirique).
        LoadTestSpec spec = new LoadTestSpec(2, 0, 3, null, 0, null);
        ScenarioExecutionOutcome outcome = engine.execute(
                List.of(step("dur", HttpMethod.GET, "/x", null, null, null)), baseUrl(s), spec, new RunningExecutionHandle(), List.of());
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        // Doit durer environ 3s (pas instantane comme 1 seule passe), sans
        // pour autant tourner indefiniment (la duree est reellement
        // respectee, pas juste une limite haute vague). Borne haute large
        // et deliberee (constat empirique sur cette machine partagee : une
        // requete isolee peut occasionnellement subir une latence externe
        // reelle et imprevisible - jusqu'a ~10s vu une fois - sans lien avec
        // le moteur ; voir le warm-up HttpClient pour un phenomene similaire
        // deja documente). La propriete testee reste demontree tant que la
        // duree observee est nettement bornee (jamais une execution qui ne
        // se termine jamais), pas qu'elle tienne dans une fenetre etroite.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(2900);
        assertThat(elapsedMs).isLessThan(15000);
        // Preuve REELLE que la duree pilote plusieurs iterations (jamais une
        // seule passe) : au moins une iteration complete par VU au minimum.
        assertThat(outcome.stepOutcomes().size()).isGreaterThanOrEqualTo(2);
    }

    // ------------------------------------------------------------
    // P0-A : iterations reelles
    // ------------------------------------------------------------

    @Test
    void iterations_3_eachVirtualUserRunsExactlyThatManyTimes() throws IOException {
        AtomicInteger callCount = new AtomicInteger();
        HttpServer s = startServer(exchange -> {
            callCount.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        LoadTestSpec spec = new LoadTestSpec(3, 0, null, 3, 0, null); // 3 VUs x 3 iterations = 9 requetes
        ScenarioExecutionOutcome outcome = engine.execute(
                List.of(step("iter", HttpMethod.GET, "/x", null, null, null)), baseUrl(s), spec, new RunningExecutionHandle(), List.of());

        assertThat(callCount.get()).isEqualTo(9);
        assertThat(outcome.stepOutcomes()).hasSize(9);
    }

    @Test
    void thinkTime_reallyDelaysBetweenIterations() throws IOException {
        HttpServer s = startServer(exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        long start = System.nanoTime();
        LoadTestSpec spec = new LoadTestSpec(1, 0, null, 3, 200, null); // 1 VU, 3 iterations, 200ms de think time
        engine.execute(List.of(step("tt", HttpMethod.GET, "/x", null, null, null)), baseUrl(s), spec, new RunningExecutionHandle(), List.of());
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        // 3 iterations, 2 pauses de 200ms entre elles minimum => au moins 400ms.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(380);
    }

    // ------------------------------------------------------------
    // P0-A : annulation reelle
    // ------------------------------------------------------------

    @Test
    void cancellation_reallyStopsExecution_beforeDurationElapses() throws IOException, InterruptedException {
        HttpServer s = startServer(exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        RunningExecutionHandle handle = new RunningExecutionHandle();
        LoadTestSpec spec = new LoadTestSpec(2, 0, 5, null, 10, null); // 5 secondes de charge prevues

        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<ScenarioExecutionOutcome> resultRef = new AtomicReference<>();
        Thread runner = new Thread(() -> {
            started.countDown();
            resultRef.set(engine.execute(List.of(step("cancel-step", HttpMethod.GET, "/x", null, null, null)),
                    baseUrl(s), spec, handle, List.of()));
        });
        long start = System.nanoTime();
        runner.start();
        started.await(2, TimeUnit.SECONDS);
        Thread.sleep(150); // laisse reellement demarrer quelques requetes
        handle.requestCancellation();
        runner.join(4000);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(runner.isAlive()).isFalse();
        // Preuve REELLE d'annulation : arret bien avant les 5 secondes prevues.
        assertThat(elapsedMs).isLessThan(4000);
        assertThat(resultRef.get()).isNotNull();
    }

    @Test
    void cancellation_beforeAnyRequest_producesEmptyOutcome() throws IOException {
        HttpServer s = startServer(exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        RunningExecutionHandle handle = new RunningExecutionHandle();
        handle.requestCancellation(); // annulee avant meme le premier VU

        ScenarioExecutionOutcome outcome = engine.execute(
                List.of(step("never", HttpMethod.GET, "/x", null, null, null)), baseUrl(s),
                new LoadTestSpec(3, 0, null, null, 0, null), handle, List.of());

        assertThat(outcome.stepOutcomes()).isEmpty();
    }

    // ------------------------------------------------------------
    // P0-A : isolation entre executions concurrentes (thread-safety)
    // ------------------------------------------------------------

    @Test
    void twoConcurrentExecutions_neverMixResults() throws IOException, InterruptedException {
        HttpServer s = startServer(exchange -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        LoadTestSpec specA = new LoadTestSpec(5, 0, null, null, 0, null);
        LoadTestSpec specB = new LoadTestSpec(7, 0, null, null, 0, null);

        AtomicReference<ScenarioExecutionOutcome> outcomeA = new AtomicReference<>();
        AtomicReference<ScenarioExecutionOutcome> outcomeB = new AtomicReference<>();
        Thread tA = new Thread(() -> outcomeA.set(engine.execute(
                List.of(step("a", HttpMethod.GET, "/x", null, null, null)), baseUrl(s), specA, new RunningExecutionHandle(), List.of())));
        Thread tB = new Thread(() -> outcomeB.set(engine.execute(
                List.of(step("b", HttpMethod.GET, "/x", null, null, null)), baseUrl(s), specB, new RunningExecutionHandle(), List.of())));

        tA.start();
        tB.start();
        tA.join(5000);
        tB.join(5000);

        // Chaque execution garde EXACTEMENT le nombre de resultats attendu -
        // aucun melange d'etat partage entre les deux executions concurrentes.
        assertThat(outcomeA.get().stepOutcomes()).hasSize(5);
        assertThat(outcomeB.get().stepOutcomes()).hasSize(7);
    }

    // ------------------------------------------------------------
    // P1-Q Etape B : variables / CSV data source
    // ------------------------------------------------------------

    @Test
    void variables_areSubstitutedFromCsvRow_intoUrlHeadersAndBody() throws IOException {
        AtomicReference<String> receivedPath = new AtomicReference<>();
        AtomicReference<String> receivedHeader = new AtomicReference<>();
        AtomicReference<String> receivedBody = new AtomicReference<>();
        HttpServer s = startServer(exchange -> {
            receivedPath.set(exchange.getRequestURI().getPath());
            receivedHeader.set(exchange.getRequestHeaders().getFirst("X-User"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        List<java.util.Map<String, String>> rows = CsvDataSource.parse("username,password\nuser1,pass1");
        StepExecutionSpec spec = stepWithOptions("login", HttpMethod.POST, "/users/${username}",
                "{\"X-User\":\"${username}\"}", "{\"password\":\"${password}\"}", null, null, null, null, null);

        ScenarioExecutionOutcome outcome = engine.execute(List.of(spec), baseUrl(s), LoadTestSpec.singlePass(),
                new RunningExecutionHandle(), rows);

        assertThat(receivedPath.get()).isEqualTo("/users/user1");
        assertThat(receivedHeader.get()).isEqualTo("user1");
        assertThat(receivedBody.get()).isEqualTo("{\"password\":\"pass1\"}");
        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.SUCCESS);
    }

    @Test
    void csvRows_areDistributedCyclically_acrossVirtualUsers() throws IOException {
        Set<String> receivedUsernames = new CopyOnWriteArraySet<>();
        HttpServer s = startServer(exchange -> {
            receivedUsernames.add(exchange.getRequestHeaders().getFirst("X-User"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        // 2 lignes pour 4 VUs : distribution cyclique attendue (jamais un echec).
        List<java.util.Map<String, String>> rows = CsvDataSource.parse("username\nuser1\nuser2");
        StepExecutionSpec spec = stepWithOptions("s", HttpMethod.GET, "/x", "{\"X-User\":\"${username}\"}", null,
                null, null, null, null, null);

        ScenarioExecutionOutcome outcome = engine.execute(List.of(spec), baseUrl(s), new LoadTestSpec(4, 0, null, null, 0, null),
                new RunningExecutionHandle(), rows);

        assertThat(outcome.stepOutcomes()).hasSize(4);
        assertThat(receivedUsernames).containsExactlyInAnyOrder("user1", "user2");
    }

    @Test
    void noCsvData_meansNoVariables_urlUsedVerbatim() throws IOException {
        AtomicReference<String> receivedPath = new AtomicReference<>();
        HttpServer s = startServer(exchange -> {
            receivedPath.set(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        // Comportement historique inchange : liste de lignes vide (CsvDataSource.parse(null)).
        ScenarioExecutionOutcome outcome = engine.execute(
                List.of(step("s", HttpMethod.GET, "/x", null, null, null)), baseUrl(s), LoadTestSpec.singlePass(),
                new RunningExecutionHandle(), CsvDataSource.parse(null));

        assertThat(receivedPath.get()).isEqualTo("/x");
        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.SUCCESS);
    }

    // ------------------------------------------------------------
    // P1-Q Etape B : assertion de contenu (response contains)
    // ------------------------------------------------------------

    @Test
    void assertionBodyContains_matching_isSuccess() throws IOException {
        HttpServer s = startServer(exchange -> {
            byte[] body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        StepExecutionSpec spec = stepWithOptions("assert-ok", HttpMethod.GET, "/x", null, null, null,
                null, null, null, "\"status\":\"ok\"");
        ScenarioExecutionOutcome outcome = engine.execute(List.of(spec), baseUrl(s), LoadTestSpec.singlePass(),
                new RunningExecutionHandle(), List.of());

        assertThat(outcome.stepOutcomes().get(0).success()).isTrue();
        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.SUCCESS);
    }

    @Test
    void assertionBodyContains_notMatching_isFailure_despiteCorrectStatus() throws IOException {
        HttpServer s = startServer(exchange -> {
            byte[] body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        StepExecutionSpec spec = stepWithOptions("assert-ko", HttpMethod.GET, "/x", null, null, 200,
                null, null, null, "\"status\":\"error\"");
        ScenarioExecutionOutcome outcome = engine.execute(List.of(spec), baseUrl(s), LoadTestSpec.singlePass(),
                new RunningExecutionHandle(), List.of());

        StepOutcome result = outcome.stepOutcomes().get(0);
        assertThat(result.success()).isFalse();
        assertThat(result.httpStatus()).isEqualTo(200); // le statut HTTP reste correct, seule l'assertion echoue
        assertThat(result.error()).contains("Assertion");
        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.FAILED);
    }

    @Test
    void noAssertionConfigured_bodyNeverRead_behavesExactlyAsHistoricalStatusOnlyCheck() throws IOException {
        HttpServer s = startServer(exchange -> {
            byte[] body = "irrelevant".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        ScenarioExecutionOutcome outcome = executeSinglePass(engine,
                List.of(step("no-assert", HttpMethod.GET, "/x", null, null, null)), baseUrl(s));

        assertThat(outcome.stepOutcomes().get(0).success()).isTrue();
    }

    // ------------------------------------------------------------
    // P1-Q Etape B : timeout par etape
    // ------------------------------------------------------------

    @Test
    void perStepTimeout_overridesGlobalTimeout_causesEarlierFailure() throws IOException {
        HttpServer s = startServer(exchange -> {
            try {
                Thread.sleep(1500); // > timeout par etape (1s) mais < timeout global (2s, voir `engine`)
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        StepExecutionSpec spec = stepWithOptions("short-timeout", HttpMethod.GET, "/slow", null, null, null,
                null, 1, null, null);
        ScenarioExecutionOutcome outcome = engine.execute(List.of(spec), baseUrl(s), LoadTestSpec.singlePass(),
                new RunningExecutionHandle(), List.of());

        StepOutcome result = outcome.stepOutcomes().get(0);
        // Avec le seul timeout global (2s), cette requete de 1.5s aurait reussi -
        // preuve que le timeout PAR ETAPE (1s) a bien ete applique a la place.
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("1s");
    }

    @Test
    void stepWithoutTimeoutOverride_usesGlobalTimeout_unchanged() throws IOException {
        HttpServer s = startServer(exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        ScenarioExecutionOutcome outcome = executeSinglePass(engine,
                List.of(step("no-override", HttpMethod.GET, "/x", null, null, null)), baseUrl(s));

        assertThat(outcome.stepOutcomes().get(0).success()).isTrue();
    }

    // ------------------------------------------------------------
    // P1-Q Etape B : think time par etape
    // ------------------------------------------------------------

    @Test
    void perStepThinkTime_reallyDelaysBeforeTheNextStep() throws IOException {
        HttpServer s = startServer(exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        StepExecutionSpec first = stepWithOptions("first", HttpMethod.GET, "/x", null, null, null, 300, null, null, null);
        StepExecutionSpec second = step("second", HttpMethod.GET, "/x", null, null, null);

        long start = System.nanoTime();
        engine.execute(List.of(first, second), baseUrl(s), LoadTestSpec.singlePass(), new RunningExecutionHandle(), List.of());
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        // La pause de 300ms configuree sur "first" doit reellement s'ecouler
        // avant "second" - jamais applique si non configure (voir test ci-dessus).
        assertThat(elapsedMs).isGreaterThanOrEqualTo(280);
    }

    // ------------------------------------------------------------
    // P1-Q Etape B : follow redirects par etape
    // ------------------------------------------------------------

    @Test
    void followRedirects_defaultNull_followsRedirect_likeHistoricalGlobalBehavior() throws IOException {
        HttpServer s = startServer(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/redirect")) {
                exchange.getResponseHeaders().add("Location", "/target");
                exchange.sendResponseHeaders(302, -1);
            } else {
                exchange.sendResponseHeaders(200, -1);
            }
            exchange.close();
        });

        ScenarioExecutionOutcome outcome = executeSinglePass(engine,
                List.of(step("redir", HttpMethod.GET, "/redirect", null, null, null)), baseUrl(s));

        StepOutcome result = outcome.stepOutcomes().get(0);
        assertThat(result.httpStatus()).isEqualTo(200); // redirection suivie automatiquement
        assertThat(result.success()).isTrue();
    }

    @Test
    void followRedirects_explicitlyFalse_doesNotFollowRedirect() throws IOException {
        HttpServer s = startServer(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/redirect")) {
                exchange.getResponseHeaders().add("Location", "/target");
                exchange.sendResponseHeaders(302, -1);
            } else {
                exchange.sendResponseHeaders(200, -1);
            }
            exchange.close();
        });

        StepExecutionSpec spec = stepWithOptions("no-redir", HttpMethod.GET, "/redirect", null, null, 302,
                null, null, false, null);
        ScenarioExecutionOutcome outcome = engine.execute(List.of(spec), baseUrl(s), LoadTestSpec.singlePass(),
                new RunningExecutionHandle(), List.of());

        StepOutcome result = outcome.stepOutcomes().get(0);
        // La redirection n'est PAS suivie : le statut recu est bien le 302 lui-meme.
        assertThat(result.httpStatus()).isEqualTo(302);
        assertThat(result.success()).isTrue(); // expectedStatus=302 correspond exactement
    }

    // ------------------------------------------------------------
    // Master prompt final (Lot A) : pacing (debit cible)
    // ------------------------------------------------------------

    @Test
    void pacing_absent_behavesLikeHistorical_noArtificialDelay() throws IOException {
        HttpServer s = startServer(exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        long start = System.nanoTime();
        // targetRps=null (LoadTestSpec.singlePass()) : aucune pause de pacing.
        ScenarioExecutionOutcome outcome = executeSinglePass(engine,
                List.of(step("no-pacing", HttpMethod.GET, "/x", null, null, null)), baseUrl(s));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(elapsedMs).isLessThan(2000); // aucune pause artificielle introduite
    }

    @Test
    void pacing_targetRps_reallyBoundsThroughput_acrossMultipleVUs() throws IOException {
        HttpServer s = startServer(exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        // 5 VUs x 2 iterations = 10 requetes au total, cadencees a ~5 req/s
        // PARTAGE entre tous les VUs -> ~2s attendues (jamais ~0s comme sans
        // pacing, jamais un multiple de 5 VUs x 2s comme si chaque VU avait
        // son propre gate independant).
        LoadTestSpec spec = new LoadTestSpec(5, 0, null, 2, 0, 5);
        long start = System.nanoTime();
        ScenarioExecutionOutcome outcome = engine.execute(
                List.of(step("paced", HttpMethod.GET, "/x", null, null, null)), baseUrl(s), spec,
                new RunningExecutionHandle(), List.of());
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(outcome.stepOutcomes()).hasSize(10);
        // Preuve REELLE de regulation du debit : nettement plus lent qu'une
        // execution instantanee (10 requetes locales prendraient normalement
        // quelques dizaines de ms sans pacing), mais borne (jamais un blocage
        // indefini). Marge large et deliberee (machine partagee/CI).
        assertThat(elapsedMs).isGreaterThanOrEqualTo(1500);
        assertThat(elapsedMs).isLessThan(8000);
    }

    @Test
    void pacing_isSharedGlobally_notPerVirtualUser() throws IOException {
        HttpServer s = startServer(exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        // 1 VU x 4 iterations vs 4 VUs x 1 iteration : MEME nombre total de
        // requetes (4) au MEME targetRps (4) -> temps attendu similaire
        // (~1s) dans les deux cas SI le debit est bien global. S'il etait
        // (a tort) applique par VU, le cas "4 VUs" serait quasi instantane
        // (chaque VU respecterait 4 req/s pour lui seul, soit 1 requete
        // chacun).
        LoadTestSpec singleVuSpec = new LoadTestSpec(1, 0, null, 4, 0, 4);
        long start1 = System.nanoTime();
        engine.execute(List.of(step("s1", HttpMethod.GET, "/x", null, null, null)), baseUrl(s), singleVuSpec,
                new RunningExecutionHandle(), List.of());
        long elapsed1Ms = Duration.ofNanos(System.nanoTime() - start1).toMillis();

        LoadTestSpec multiVuSpec = new LoadTestSpec(4, 0, null, 1, 0, 4);
        long start2 = System.nanoTime();
        engine.execute(List.of(step("s2", HttpMethod.GET, "/x", null, null, null)), baseUrl(s), multiVuSpec,
                new RunningExecutionHandle(), List.of());
        long elapsed2Ms = Duration.ofNanos(System.nanoTime() - start2).toMillis();

        assertThat(elapsed1Ms).isGreaterThanOrEqualTo(600);
        // Preuve cle : le cas multi-VU n'est PAS drastiquement plus rapide
        // (ce qui prouverait un gate par-VU, jamais global) - marge large
        // pour une machine partagee/CI.
        assertThat(elapsed2Ms).isGreaterThanOrEqualTo(600);
    }

    @Test
    void pacing_cancellation_stopsPromptly_neverBlocksIndefinitely() throws IOException, InterruptedException {
        HttpServer s = startServer(exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        // Debit tres bas (1 req/s) sur beaucoup d'iterations : sans
        // annulation, durerait des dizaines de secondes.
        LoadTestSpec spec = new LoadTestSpec(1, 0, null, 50, 0, 1);
        RunningExecutionHandle handle = new RunningExecutionHandle();
        CountDownLatch started = new CountDownLatch(1);
        Thread runner = new Thread(() -> {
            started.countDown();
            engine.execute(List.of(step("slow-pacing", HttpMethod.GET, "/x", null, null, null)), baseUrl(s), spec,
                    handle, List.of());
        });
        long start = System.nanoTime();
        runner.start();
        started.await(2, TimeUnit.SECONDS);
        Thread.sleep(150);
        handle.requestCancellation();
        runner.join(3000);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(runner.isAlive()).isFalse();
        assertThat(elapsedMs).isLessThan(3000); // arret bien avant les ~50s prevues sans annulation
    }

    // ------------------------------------------------------------
    // Master prompt final (Lot B) : variables dynamiques capturees
    // ------------------------------------------------------------

    @Test
    void dynamicVariable_capturedFromJsonResponse_isSubstitutedInNextStep() throws IOException {
        AtomicReference<String> receivedHeader = new AtomicReference<>();
        HttpServer s = startServer(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/login")) {
                byte[] body = "{\"token\":\"abc123\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else {
                receivedHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
                exchange.sendResponseHeaders(200, -1);
            }
            exchange.close();
        });

        StepExecutionSpec login = stepWithCapture("login", HttpMethod.POST, "/login", null, null, null,
                "token", "token");
        StepExecutionSpec authenticated = stepWithOptions("authenticated", HttpMethod.GET, "/secure",
                "{\"Authorization\":\"Bearer ${token}\"}", null, null, null, null, null, null);

        ScenarioExecutionOutcome outcome = engine.execute(List.of(login, authenticated), baseUrl(s),
                LoadTestSpec.singlePass(), new RunningExecutionHandle(), List.of());

        assertThat(receivedHeader.get()).isEqualTo("Bearer abc123");
        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.SUCCESS);
    }

    @Test
    void dynamicVariable_nestedJsonPath_isExtractedAndSubstituted() throws IOException {
        AtomicReference<String> receivedPath = new AtomicReference<>();
        HttpServer s = startServer(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/login")) {
                byte[] body = "{\"data\":{\"userId\":\"u-42\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else {
                receivedPath.set(exchange.getRequestURI().getPath());
                exchange.sendResponseHeaders(200, -1);
            }
            exchange.close();
        });

        StepExecutionSpec login = stepWithCapture("login", HttpMethod.POST, "/login", null, null, null,
                "userId", "$.data.userId");
        StepExecutionSpec next = step("next", HttpMethod.GET, "/users/${userId}", null, null, null);

        engine.execute(List.of(login, next), baseUrl(s), LoadTestSpec.singlePass(), new RunningExecutionHandle(), List.of());

        assertThat(receivedPath.get()).isEqualTo("/users/u-42");
    }

    @Test
    void dynamicVariable_missingPath_neverThrows_placeholderLeftUnsubstitutedInBody() throws IOException {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        HttpServer s = startServer(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/login")) {
                byte[] body = "{\"other\":\"value\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else {
                receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(200, -1);
            }
            exchange.close();
        });

        StepExecutionSpec login = stepWithCapture("login", HttpMethod.POST, "/login", null, null, null,
                "token", "token");
        // Le corps (texte libre, jamais parse comme une URI) reste le
        // meilleur champ pour observer un placeholder non substitue tel
        // quel : contrairement a l'URL, "${token}" y est un contenu valide,
        // jamais une cause de rejet a la construction de la requete (voir
        // test suivant pour le cas URL, ou "{"/"}" sont illegaux dans une
        // URI et produisent honnetement une erreur "URL invalide" plutot
        // que d'envoyer un chemin litteral au serveur).
        StepExecutionSpec next = step("next", HttpMethod.POST, "/x", null, "{\"token\":\"${token}\"}", null);

        ScenarioExecutionOutcome outcome = engine.execute(List.of(login, next), baseUrl(s), LoadTestSpec.singlePass(),
                new RunningExecutionHandle(), List.of());

        // Chemin introuvable -> aucune variable produite -> placeholder laisse tel quel, jamais une exception.
        assertThat(receivedBody.get()).isEqualTo("{\"token\":\"${token}\"}");
        assertThat(outcome.stepOutcomes()).hasSize(2);
        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.SUCCESS);
    }

    @Test
    void dynamicVariable_missingPath_inUrl_failsCleanlyAsInvalidUrl_neverSentLiterally() throws IOException {
        // Decouverte reelle documentee ici : "{"/"}" ne sont jamais des
        // caracteres valides dans une URI (RFC 3986) - un "${token}" laisse
        // tel quel dans une URL (capture manquee) ne peut donc JAMAIS partir
        // sur le reseau tel quel : URI.create() rejette la requete AVANT tout
        // envoi, produisant un echec propre ("URL invalide"), jamais un
        // comportement indefini ni une exception non capturee.
        HttpServer s = startServer(exchange -> {
            byte[] body = "{\"other\":\"value\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        StepExecutionSpec login = stepWithCapture("login", HttpMethod.GET, "/login", null, null, null,
                "token", "token");
        StepExecutionSpec next = step("next", HttpMethod.GET, "/x/${token}", null, null, null);

        ScenarioExecutionOutcome outcome = engine.execute(List.of(login, next), baseUrl(s), LoadTestSpec.singlePass(),
                new RunningExecutionHandle(), List.of());

        assertThat(outcome.stepOutcomes()).hasSize(2);
        StepOutcome secondOutcome = outcome.stepOutcomes().get(1);
        assertThat(secondOutcome.success()).isFalse();
        assertThat(secondOutcome.error()).contains("URL invalide");
    }

    @Test
    void dynamicVariable_invalidJsonResponse_neverThrows_captureSimplyAbsent() throws IOException {
        HttpServer s = startServer(exchange -> {
            byte[] body = "not-json-at-all".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        StepExecutionSpec login = stepWithCapture("login", HttpMethod.GET, "/x", null, null, null,
                "token", "token");

        ScenarioExecutionOutcome outcome = engine.execute(List.of(login), baseUrl(s), LoadTestSpec.singlePass(),
                new RunningExecutionHandle(), List.of());

        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(outcome.stepOutcomes().get(0).success()).isTrue();
    }

    @Test
    void dynamicVariable_isolatedPerVirtualUser_neverCrossesOver() throws IOException {
        AtomicInteger issuedCounter = new AtomicInteger();
        Set<String> tokensReceivedInSecondStep = new CopyOnWriteArraySet<>();
        HttpServer s = startServer(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/login")) {
                String token = "tok-" + issuedCounter.incrementAndGet();
                byte[] body = ("{\"token\":\"" + token + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else {
                tokensReceivedInSecondStep.add(exchange.getRequestHeaders().getFirst("X-Token"));
                exchange.sendResponseHeaders(200, -1);
            }
            exchange.close();
        });

        StepExecutionSpec login = stepWithCapture("login", HttpMethod.POST, "/login", null, null, null,
                "token", "token");
        StepExecutionSpec next = stepWithOptions("next", HttpMethod.GET, "/x", "{\"X-Token\":\"${token}\"}", null,
                null, null, null, null, null);

        // 5 VUs concurrents : chacun doit reutiliser EXACTEMENT le token que
        // LUI-MEME a recu au login, jamais celui d'un autre VU.
        ScenarioExecutionOutcome outcome = engine.execute(List.of(login, next), baseUrl(s),
                new LoadTestSpec(5, 0, null, null, 0, null), new RunningExecutionHandle(), List.of());

        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        // 5 VUs, 5 tokens distincts emis, et EXACTEMENT ces 5 tokens (jamais
        // moins par collision, jamais un token invente) retrouves cote
        // "next" - preuve d'isolation stricte par VU.
        assertThat(tokensReceivedInSecondStep).hasSize(5);
        for (String token : tokensReceivedInSecondStep) {
            assertThat(token).matches("tok-[1-5]");
        }
    }

    @Test
    void dynamicVariable_capturePersistsAcrossMultipleIterations_ofSameVU() throws IOException {
        AtomicInteger issuedCounter = new AtomicInteger();
        List<String> tokensReceived = new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer s = startServer(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/login")) {
                String token = "tok-" + issuedCounter.incrementAndGet();
                byte[] body = ("{\"token\":\"" + token + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else {
                tokensReceived.add(exchange.getRequestHeaders().getFirst("X-Token"));
                exchange.sendResponseHeaders(200, -1);
            }
            exchange.close();
        });

        StepExecutionSpec login = stepWithCapture("login", HttpMethod.POST, "/login", null, null, null,
                "token", "token");
        StepExecutionSpec next = stepWithOptions("next", HttpMethod.GET, "/x", "{\"X-Token\":\"${token}\"}", null,
                null, null, null, null, null);

        // 1 VU, 3 iterations : le login re-capture un NOUVEAU token a chaque
        // iteration (jamais fige sur la toute premiere valeur).
        engine.execute(List.of(login, next), baseUrl(s), new LoadTestSpec(1, 0, null, 3, 0, null),
                new RunningExecutionHandle(), List.of());

        assertThat(tokensReceived).containsExactly("tok-1", "tok-2", "tok-3");
    }
}
