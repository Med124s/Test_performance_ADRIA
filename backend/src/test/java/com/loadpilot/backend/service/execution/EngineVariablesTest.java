package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.enums.CsvDistribution;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.enums.HttpMethod;
import com.loadpilot.backend.service.auth.ApplicationAuthSpec;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Variables en test de charge reel : contexte propre a chaque VU, captures videes a chaque
 * iteration, distribution du CSV (par VU / par iteration), variables du scenario, echec avant
 * envoi d'une variable introuvable, URL finale tracee par VU et par iteration.
 */
class EngineVariablesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static HttpServer server;
    private static String baseUrl;

    /** Virements crees (id -> VU createur) et supprimes (id), cote "serveur". */
    private static final Map<String, String> created = new ConcurrentHashMap<>();
    private static final List<String> deleted = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicInteger seq = new AtomicInteger();
    private static final AtomicInteger captureCalls = new AtomicInteger();
    private static final AtomicInteger useCalls = new AtomicInteger();

    private final HttpClientExecutionEngine engine = new HttpClientExecutionEngine(5, MAPPER, EXECUTOR);

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", EngineVariablesTest::handle);
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
        created.clear();
        deleted.clear();
        bodies.clear();
        captureCalls.set(0);
        useCalls.set(0);
    }

    private static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getRawPath();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String method = ex.getRequestMethod();
        int status = 200;
        String response = "{}";
        if (method.equals("POST") && path.equals("/transfers")) {
            String id = "TRF" + seq.incrementAndGet();
            created.put(id, ex.getRequestHeaders().getFirst("X-VU"));
            bodies.add(body);
            status = 201;
            response = "{\"transferId\":\"" + id + "\"}";
        } else if (method.equals("DELETE") && path.startsWith("/transfers/")) {
            String id = path.substring("/transfers/".length());
            status = created.containsKey(id) && !deleted.contains(id) ? 200 : 404;
            if (status == 200) deleted.add(id);
        } else if (path.equals("/once")) {
            // Ne renvoie l'id qu'au tout premier appel : les iterations suivantes n'ont rien a capturer.
            response = captureCalls.incrementAndGet() == 1 ? "{\"id\":\"FIRST\"}" : "{}";
        } else if (path.startsWith("/use/")) {
            useCalls.incrementAndGet();
        } else if (path.equals("/echo")) {
            bodies.add(body);
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static StepExecutionSpec spec(String name, HttpMethod method, String url, String headers, String body,
            String captureName, String capturePath) {
        return new StepExecutionSpec(UUID.randomUUID(), name, method, url, headers, body, null, null, null, null, null,
                captureName, capturePath);
    }

    @Test
    void eachVirtualUser_deletesExactlyTheTransferItCreated_inTheSameIteration() {
        List<StepExecutionSpec> steps = List.of(
                spec("create", HttpMethod.POST, "/transfers", "{\"X-VU\":\"${vu}\"}", "{\"amount\": ${amount}}", "transferId", "$.transferId"),
                spec("delete", HttpMethod.DELETE, "/transfers/:transferId", null, null, null, null));
        List<Map<String, String>> rows = List.of(Map.of("vu", "A", "amount", "10"), Map.of("vu", "B", "amount", "20"),
                Map.of("vu", "C", "amount", "30"), Map.of("vu", "D", "amount", "40"), Map.of("vu", "E", "amount", "50"));

        ScenarioExecutionOutcome outcome = engine.execute(steps, baseUrl, ApplicationAuthSpec.none(),
                new LoadTestSpec(5, 0, null, 4, 0, null), new RunningExecutionHandle(),
                new VariableSources(rows, CsvDistribution.PER_VIRTUAL_USER, Map.of(), steps));

        assertThat(outcome.finalStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(outcome.stepOutcomes()).hasSize(40);
        assertThat(created).hasSize(20);
        assertThat(deleted).hasSize(20).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(created.keySet());
        // Chaque (VU, iteration) supprime l'id qu'elle a cree : traces VU/iteration/URL finale.
        Map<String, List<StepOutcome>> byIteration = outcome.stepOutcomes().stream()
                .collect(Collectors.groupingBy(o -> o.virtualUser() + "/" + o.iteration()));
        assertThat(byIteration).hasSize(20);
        byIteration.values().forEach(pair -> assertThat(pair).hasSize(2));
        // Le VU n a toujours la ligne CSV n : tous les virements d'un VU portent son marqueur.
        Map<Integer, Set<String>> creatorsByVu = new HashMap<>();
        outcome.stepOutcomes().stream().filter(o -> o.requestUrl().contains("/transfers/")).forEach(o -> {
            String id = o.requestUrl().substring(o.requestUrl().lastIndexOf('/') + 1);
            creatorsByVu.computeIfAbsent(o.virtualUser(), k -> new java.util.HashSet<>()).add(created.get(id));
        });
        assertThat(creatorsByVu).hasSize(5);
        creatorsByVu.values().forEach(markers -> assertThat(markers).hasSize(1));
    }

    @Test
    void capturesAreResetEachIteration_andAMissingVariableFailsBeforeSending() {
        List<StepExecutionSpec> steps = List.of(
                spec("capture", HttpMethod.GET, "/once", null, null, "id", "$.id"),
                spec("use", HttpMethod.GET, "/use/${id}", null, null, null, null));

        ScenarioExecutionOutcome outcome = engine.execute(steps, baseUrl, ApplicationAuthSpec.none(),
                new LoadTestSpec(1, 0, null, 3, 0, null), new RunningExecutionHandle(),
                new VariableSources(List.of(), null, Map.of(), steps));

        // Iteration 0 : capture puis utilisation ; iterations 1 et 2 : rien capture -> echec avant envoi.
        assertThat(useCalls.get()).isEqualTo(1);
        List<StepOutcome> failures = outcome.stepOutcomes().stream().filter(o -> !o.success()).toList();
        assertThat(failures).hasSize(2);
        assertThat(failures).allSatisfy(f -> {
            assertThat(f.httpStatus()).isNull();
            assertThat(f.error()).isEqualTo("Variable ${id} introuvable : vérifiez la capture de l'étape 1 ou le CSV.");
        });
        assertThat(failures).extracting(StepOutcome::iteration).containsExactly(1, 2);
    }

    @Test
    void perIterationDistribution_givesANewRowToEveryIteration_sharedAcrossVirtualUsers() {
        List<StepExecutionSpec> steps = List.of(spec("echo", HttpMethod.POST, "/echo", null, "${n}", null, null));
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i <= 6; i++) rows.add(Map.of("n", "row" + i));

        engine.execute(steps, baseUrl, ApplicationAuthSpec.none(), new LoadTestSpec(2, 0, null, 3, 0, null),
                new RunningExecutionHandle(), new VariableSources(rows, CsvDistribution.PER_ITERATION, Map.of(), steps));

        assertThat(bodies).containsExactlyInAnyOrder("row1", "row2", "row3", "row4", "row5", "row6");
    }

    @Test
    void scenarioVariables_areUsed_afterCsv_andValuesAreEncodedInTheFinalUrl() {
        List<StepExecutionSpec> steps = List.of(new StepExecutionSpec(UUID.randomUUID(), "echo", HttpMethod.POST, "/echo",
                null, "${currency}|${amount}", null, null, null, null, null, null, null, null,
                "[{\"key\":\"label\",\"value\":\"${label}\",\"enabled\":true}]"));

        ScenarioExecutionOutcome outcome = engine.execute(steps, baseUrl, ApplicationAuthSpec.none(),
                new LoadTestSpec(1, 0, null, 1, 0, null), new RunningExecutionHandle(),
                new VariableSources(List.of(Map.of("amount", "150")), CsvDistribution.PER_VIRTUAL_USER,
                        Map.of("currency", "MAD", "amount", "1", "label", "Loyer & charges"), steps));

        assertThat(bodies).containsExactly("MAD|150");
        assertThat(outcome.stepOutcomes().get(0).requestUrl()).isEqualTo(baseUrl + "/echo?label=Loyer%20%26%20charges");
    }
}
