package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.dto.request.ApplicationRequest;
import com.loadpilot.backend.dto.request.ExecutionRequest;
import com.loadpilot.backend.dto.request.StepRequest;
import com.loadpilot.backend.dto.request.StressSeriesRequest;
import com.loadpilot.backend.enums.HttpMethod;
import com.loadpilot.backend.service.execution.RunningExecutionRegistry;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Serie d'essais de stress par l'API (environ 5 minutes ; pause reduite a 3 s) : essais enchaines, serveur reserve
 * pendant la pause, point de rupture consolide ; annulation pendant la pause ; essai non concluant qui arrete la serie.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "app.stress.series-pause-seconds=3")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StressSeriesTest {

    /** Au-dela de ce nombre d'arrivees dans la derniere seconde, la cible repond en 800 ms. */
    private static final int CAPACITY_RPS = 6;
    private static final Deque<Long> arrivals = new ArrayDeque<>();
    private static HttpServer target;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RunningExecutionRegistry registry;
    @Autowired
    private com.loadpilot.backend.service.impl.StressSeriesService stressSeriesService;
    @Autowired
    private com.loadpilot.backend.repository.StressSeriesRepository seriesRepository;
    @Autowired
    private com.loadpilot.backend.repository.ScenarioRepository scenarioRepository;
    @MockBean
    private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_SUPER_ADMIN =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));

    @BeforeAll
    static void startTarget() throws IOException {
        target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.createContext("/", ex -> {
            boolean overloaded;
            synchronized (arrivals) {
                long now = System.nanoTime();
                arrivals.addLast(now);
                while (!arrivals.isEmpty() && arrivals.peekFirst() < now - 1_000_000_000L) arrivals.removeFirst();
                overloaded = arrivals.size() > CAPACITY_RPS;
            }
            if (overloaded) {
                try {
                    Thread.sleep(800);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        target.setExecutor(Executors.newCachedThreadPool());
        target.start();
    }

    @AfterAll
    static void stopTarget() {
        target.stop(0);
    }

    private MockHttpServletResponse postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url).with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(body instanceof String s ? s : objectMapper.writeValueAsString(body)))
                .andReturn().getResponse();
    }

    private JsonNode json(String url) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get(url).with(AS_SUPER_ADMIN)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Application et scenario d'une etape ; "loadMode" : STRESS ou MANUAL. */
    private String scenario(String name, String baseUrl, String loadMode) throws Exception {
        String appId = objectMapper.readTree(postJson("/api/applications", new ApplicationRequest(name + "-app", "d", baseUrl))
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("id").asText();
        String scenarioId = objectMapper.readTree(postJson("/api/scenarios", objectMapper.createObjectNode()
                .put("applicationId", appId).put("name", name).put("loadMode", loadMode).toString()).getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .get("id").asText();
        postJson("/api/steps", new StepRequest(UUID.fromString(scenarioId), "etape", HttpMethod.GET, "/x", null, null, 1, 200,
                null, null, null, null, null, null));
        return scenarioId;
    }

    private String targetUrl() {
        return "http://localhost:" + target.getAddress().getPort();
    }

    private JsonNode waitForSeries(String seriesId, long timeoutMs, java.util.function.Predicate<JsonNode> done) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        JsonNode series = json("/api/stress-series/" + seriesId);
        while (System.currentTimeMillis() < deadline && !done.test(series)) {
            Thread.sleep(500);
            series = json("/api/stress-series/" + seriesId);
        }
        return series;
    }

    @Test
    void invalidRunCounts_andManualScenarios_areRefused() throws Exception {
        String stress = scenario("serie-refus-stress", targetUrl(), "STRESS");
        String manual = scenario("serie-refus-manuel", targetUrl(), "MANUAL");

        for (int runs : new int[] {1, 2, 4, 6}) {
            MockHttpServletResponse refused = postJson("/api/stress-series", new StressSeriesRequest(UUID.fromString(stress), runs));
            assertThat(refused.getStatus()).as("%d essais", runs).isEqualTo(400);
        }
        MockHttpServletResponse notStress = postJson("/api/stress-series", new StressSeriesRequest(UUID.fromString(manual), 3));
        assertThat(notStress.getStatus()).isEqualTo(400);
        assertThat(notStress.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("mode « Point de rupture »");
    }

    @Test
    void threeRuns_areChained_theServerStaysReserved_andTheBreakpointIsConsolidated() throws Exception {
        String scenarioId = scenario("serie-trois", targetUrl(), "STRESS");
        String manualId = scenario("serie-trois-manuel", targetUrl(), "MANUAL");

        MockHttpServletResponse started = postJson("/api/stress-series", new StressSeriesRequest(UUID.fromString(scenarioId), 3));
        assertThat(started.getStatus()).isEqualTo(202);
        JsonNode series = objectMapper.readTree(started.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        String seriesId = series.get("id").asText();
        assertThat(series.get("status").asText()).isEqualTo("RUNNING");
        assertThat(series.get("requestedRuns").asInt()).isEqualTo(3);
        String firstRun = series.get("currentExecutionId").asText();
        JsonNode execution = json("/api/executions/" + firstRun);
        assertThat(execution.get("series").get("id").asText()).isEqualTo(seriesId);
        assertThat(execution.get("series").get("index").asInt()).isEqualTo(1);

        // Pendant un essai, puis pendant la pause : le serveur reste reserve a la serie.
        assertThat(postJson("/api/executions", new ExecutionRequest(UUID.fromString(manualId))).getStatus()).isEqualTo(429);
        JsonNode paused = waitForSeries(seriesId, 150_000, s -> !s.get("nextRunAt").isNull() || !"RUNNING".equals(s.get("status").asText()));
        assertThat(paused.get("nextRunAt").isNull()).as("pause apres le premier essai").isFalse();
        assertThat(mockMvc.perform(get("/api/stress-series/" + seriesId + "/report/pdf").with(AS_SUPER_ADMIN))
                .andReturn().getResponse().getStatus()).as("PDF une fois la serie terminee").isEqualTo(409);
        assertThat(paused.get("currentExecutionId").isNull()).isTrue();
        MockHttpServletResponse duringPause = postJson("/api/executions", new ExecutionRequest(UUID.fromString(manualId)));
        assertThat(duringPause.getStatus()).isEqualTo(429);
        assertThat(duringPause.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("en pause entre deux essais");
        // Pas de modification du scenario pendant la serie.
        assertThat(mockMvc.perform(put("/api/scenarios/" + scenarioId).with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.createObjectNode().put("applicationId", json("/api/scenarios/" + scenarioId).get("applicationId").asText())
                        .put("name", "renomme")
                        .toString())).andReturn().getResponse().getStatus()).isEqualTo(409);

        JsonNode done = waitForSeries(seriesId, 400_000, s -> !"RUNNING".equals(s.get("status").asText()));
        assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(done.get("finishedAt").isNull()).isFalse();
        List<Integer> indexes = new ArrayList<>();
        done.get("runs").forEach(r -> {
            indexes.add(r.get("index").asInt());
            assertThat(r.get("status").asText()).isEqualTo("THRESHOLDS_EXCEEDED");
            assertThat(r.get("stress").get("outcome").asText()).isEqualTo("BREAKPOINT");
        });
        assertThat(indexes).containsExactly(1, 2, 3);
        JsonNode summary = done.get("summary");
        assertThat(summary.get("ruptures").asInt()).isEqualTo(3);
        assertThat(summary.get("verdict").asText()).isIn("CONFIRMED", "DISPERSED");
        assertThat(summary.get("minRps").asDouble()).isLessThanOrEqualTo(summary.get("meanRps").asDouble());
        assertThat(summary.get("message").asText()).containsAnyOf("Point de rupture confirmé ", "Résultats dispersés")
                .contains("3 essais");

        byte[] pdf = mockMvc.perform(get("/api/stress-series/" + seriesId + "/report/pdf").with(AS_SUPER_ADMIN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        // Serie terminee : serveur libere.
        Thread.sleep(500);
        assertThat(registry.reservedVirtualUsersSnapshot()).isZero();
        MockHttpServletResponse manualRun = postJson("/api/executions", new ExecutionRequest(UUID.fromString(manualId)));
        assertThat(manualRun.getStatus()).isEqualTo(202);
        postJson("/api/executions/" + objectMapper.readTree(manualRun.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("id").asText() + "/cancel", "{}");
    }

    @Test
    void cancellingDuringThePause_stopsTheSeries_andFreesTheServer() throws Exception {
        String scenarioId = scenario("serie-annulee", targetUrl(), "STRESS");
        String seriesId = objectMapper.readTree(postJson("/api/stress-series", new StressSeriesRequest(UUID.fromString(scenarioId), 3))
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("id").asText();

        JsonNode paused = waitForSeries(seriesId, 150_000, s -> !s.get("nextRunAt").isNull() || !"RUNNING".equals(s.get("status").asText()));
        assertThat(paused.get("nextRunAt").isNull()).isFalse();
        MockHttpServletResponse cancelled = postJson("/api/stress-series/" + seriesId + "/cancel", "{}");
        assertThat(cancelled.getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(cancelled.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(registry.reservedVirtualUsersSnapshot()).isZero();

        // Fin de la pause : aucun essai suivant.
        Thread.sleep(5_000);
        JsonNode after = json("/api/stress-series/" + seriesId);
        assertThat(after.get("runs")).hasSize(1);
        assertThat(after.get("stopReason").asText()).startsWith("Annulée par ");
        assertThat(after.get("summary").get("verdict").asText()).isEqualTo("NOT_CONFIRMED");
        assertThat(postJson("/api/stress-series/" + seriesId + "/cancel", "{}").getStatus()).isEqualTo(409);
    }

    @Test
    void aRunThatCannotMeasureItsBaseline_interruptsTheSeries() throws Exception {
        int closedPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        String scenarioId = scenario("serie-interrompue", "http://localhost:" + closedPort, "STRESS");
        String seriesId = objectMapper.readTree(postJson("/api/stress-series", new StressSeriesRequest(UUID.fromString(scenarioId), 3))
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("id").asText();

        JsonNode done = waitForSeries(seriesId, 120_000, s -> !"RUNNING".equals(s.get("status").asText()));
        assertThat(done.get("status").asText()).isEqualTo("INTERRUPTED");
        assertThat(done.get("runs")).hasSize(1);
        assertThat(done.get("runs").get(0).get("status").asText()).isEqualTo("FAILED");
        assertThat(done.get("stopReason").asText()).startsWith("Essai 1/3 échoué : ").contains("Baseline impossible");
        assertThat(done.get("summary").get("verdict").asText()).isEqualTo("NONE");
        Thread.sleep(500);
        assertThat(registry.reservedVirtualUsersSnapshot()).isZero();
    }

    @Test
    void aSeriesLeftRunningByARestart_isInterruptedAtStartup() throws Exception {
        String scenarioId = scenario("serie-redemarrage", targetUrl(), "STRESS");
        UUID seriesId = seriesRepository.save(com.loadpilot.backend.entity.StressSeries.builder()
                .scenario(scenarioRepository.findById(UUID.fromString(scenarioId)).orElseThrow())
                .requestedRuns(3).status(com.loadpilot.backend.enums.StressSeriesStatus.RUNNING)
                .startedAt(java.time.Instant.now()).nextRunAt(java.time.Instant.now()).build()).getId();

        stressSeriesService.run(null);

        JsonNode series = json("/api/stress-series/" + seriesId);
        assertThat(series.get("status").asText()).isEqualTo("INTERRUPTED");
        assertThat(series.get("stopReason").asText()).isEqualTo("Le serveur LoadPilot a redémarré pendant la série.");
        assertThat(series.get("nextRunAt").isNull()).isTrue();
    }
}
