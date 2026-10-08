package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.entity.ExecutionStepResult;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.ExecutionStepResultRepository;
import com.loadpilot.backend.repository.ScenarioRepository;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Cartes Minimum / Maximum du rapport : requete la plus rapide / la plus lente, top 10, coherence
 * avec les cartes et l'analyse dans le temps, tri de la liste des requetes, execution sans requete,
 * et rapidite au-dela de 10 000 requetes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExecutionExtremesTest {

    private static HttpServer server;
    private static String targetUrl;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ExecutionRepository executionRepository;
    @Autowired
    private ExecutionStepResultRepository resultRepository;
    @Autowired
    private ScenarioRepository scenarioRepository;
    @Autowired
    private com.loadpilot.backend.service.execution.RunningExecutionRegistry registry;
    @MockBean
    private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_ADMIN = jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", ex -> {
            // Temps de reponse varies (quelques requetes nettement plus lentes).
            int r = ThreadLocalRandom.current().nextInt(1000);
            try {
                Thread.sleep(r < 3 ? 60 + r * 20 : r % 4);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = "{\"success\":true}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.setExecutor(Executors.newFixedThreadPool(32));
        server.start();
        targetUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private JsonNode json(MockHttpServletRequestBuilder builder, Object body) throws Exception {
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        String s = mockMvc.perform(builder.with(AS_ADMIN)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return s.isBlank() ? null : objectMapper.readTree(s);
    }

    private static List<Long> times(JsonNode extremes) {
        List<Long> out = new ArrayList<>();
        extremes.get("requests").forEach(r -> out.add(r.get("request").get("responseTime").asLong()));
        return out;
    }

    @Test
    void minAndMax_top10_consistentWithCardsAndTimeline_fastWithMoreThan10000Requests() throws Exception {
        String appId = json(post("/api/applications"), Map.of("name", "extremes-" + UUID.randomUUID(), "url", targetUrl)).get("id").asText();
        String scenarioId = json(post("/api/scenarios"), Map.of("applicationId", appId, "name", "Extremes", "virtualUsers", 10,
                "rampUpSeconds", 0, "iterations", 1100, "thinkTimeMs", 0)).get("id").asText();
        json(post("/api/steps"), Map.of("scenarioId", scenarioId, "name", "Lire", "method", "GET", "url", "/lire", "order", 1,
                "expectedStatus", 200));
        String id = json(post("/api/executions"), Map.of("scenarioId", scenarioId)).get("id").asText();
        // Attente de la fin COMPLETE de la tache asynchrone (resultats, metriques, chronologie, resume) :
        // aucun travail ne doit continuer en arriere-plan pendant les tests suivants.
        long deadline = System.currentTimeMillis() + 240_000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(300);
            String status = json(get("/api/executions/" + id + "/status"), null).get("status").asText();
            if (List.of("SUCCESS", "THRESHOLDS_EXCEEDED", "FAILED").contains(status) && registry.get(UUID.fromString(id)) == null) break;
        }
        JsonNode stats = json(get("/api/executions/" + id + "/report"), null).get("statistics");
        assertThat(stats.get("totalRequests").asInt()).isEqualTo(11_000);

        long started = System.nanoTime();
        JsonNode max = json(get("/api/executions/" + id + "/extremes").param("kind", "MAX"), null);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        JsonNode min = json(get("/api/executions/" + id + "/extremes").param("kind", "MIN"), null);

        // Valeurs des panneaux = valeurs des cartes.
        assertThat(max.get("cardValueMs").asLong()).isEqualTo(stats.get("maxResponseTime").asLong());
        assertThat(min.get("cardValueMs").asLong()).isEqualTo(stats.get("minResponseTime").asLong());
        assertThat(times(max).get(0)).isEqualTo(stats.get("maxResponseTime").asLong());
        assertThat(times(min).get(0)).isEqualTo(stats.get("minResponseTime").asLong());
        assertThat(max.get("measuredRequests").asLong()).isEqualTo(11_000);

        // Top 10 : exactement les 10 plus grands (plus petits) temps de toutes les requetes.
        List<Long> all = resultRepository.findByExecutionIdOrderByTimestampAsc(UUID.fromString(id)).stream()
                .map(ExecutionStepResult::getResponseTime).sorted().toList();
        assertThat(times(min)).containsExactlyElementsOf(all.subList(0, 10));
        assertThat(times(max)).containsExactlyElementsOf(all.subList(all.size() - 10, all.size()).reversed());

        // Informations de chaque requete et contexte au meme moment.
        JsonNode slowest = max.get("requests").get(0);
        assertThat(slowest.get("request").get("stepName").asText()).isEqualTo("Lire");
        assertThat(slowest.get("request").get("method").asText()).isEqualTo("GET");
        assertThat(slowest.get("request").get("virtualUser").isNull()).isFalse();
        assertThat(slowest.get("request").get("iteration").isNull()).isFalse();
        assertThat(slowest.get("request").get("httpStatus").asInt()).isEqualTo(200);
        double at = slowest.get("secondsFromStart").asDouble();
        assertThat(at).isGreaterThanOrEqualTo(slowest.get("intervalStartSeconds").asDouble())
                .isLessThan(slowest.get("intervalStartSeconds").asDouble() + max.get("intervalSeconds").asInt() + 0.1);
        assertThat(slowest.get("activeVirtualUsers").asInt()).isBetween(1, 10);
        assertThat(slowest.get("throughput").asDouble()).isPositive();
        assertThat(slowest.get("p95Ms").isNull()).isFalse();

        // Analyse dans le temps : reperes sur les memes requetes.
        JsonNode tl = json(get("/api/executions/" + id + "/timeline"), null);
        assertThat(tl.get("slowest").get("responseTimeMs").asLong()).isEqualTo(stats.get("maxResponseTime").asLong());
        assertThat(tl.get("fastest").get("responseTimeMs").asLong()).isEqualTo(stats.get("minResponseTime").asLong());
        assertThat(tl.get("t").get(tl.get("slowest").get("bucket").asInt()).asInt()).isEqualTo(slowest.get("intervalStartSeconds").asInt());

        // Liste des requetes d'une etape triee par temps de reponse (plus lentes / plus rapides d'abord).
        JsonNode stepId = json(get("/api/executions/" + id + "/report"), null).get("steps").get(0).get("stepId");
        JsonNode bySlowest = json(get("/api/executions/" + id + "/results").param("stepId", stepId.asText()).param("sort", "SLOWEST")
                .param("size", "10"), null);
        List<Long> sortedRows = new ArrayList<>();
        bySlowest.get("content").forEach(r -> sortedRows.add(r.get("responseTime").asLong()));
        assertThat(sortedRows).isEqualTo(times(max));
        JsonNode byFastest = json(get("/api/executions/" + id + "/results").param("stepId", stepId.asText()).param("sort", "FASTEST")
                .param("size", "10"), null);
        assertThat(byFastest.get("content").get(0).get("responseTime").asLong()).isEqualTo(stats.get("minResponseTime").asLong());
        assertThat(sortedRows).isSortedAccordingTo(Comparator.reverseOrder());

        assertThat(elapsedMs).as("calcule par la base, sans charger les 11 000 requetes").isLessThan(3000);
    }

    @Test
    void executionWithoutRequests_givesEmptyPanels_andInvalidKindIsRejected() throws Exception {
        String appId = json(post("/api/applications"), Map.of("name", "extremes-vide-" + UUID.randomUUID(), "url", targetUrl)).get("id").asText();
        String scenarioId = json(post("/api/scenarios"), Map.of("applicationId", appId, "name", "Vide", "virtualUsers", 1,
                "rampUpSeconds", 0, "iterations", 1, "thinkTimeMs", 0)).get("id").asText();
        Execution empty = executionRepository.save(Execution.builder().scenario(scenarioRepository.findById(UUID.fromString(scenarioId)).orElseThrow())
                .startedAt(Instant.now()).finishedAt(Instant.now()).status(ExecutionStatus.CANCELLED).totalSteps(1).successfulSteps(0)
                .failedSteps(0).duration(0L).build());

        JsonNode max = json(get("/api/executions/" + empty.getId() + "/extremes").param("kind", "MAX"), null);
        assertThat(max.get("cardValueMs").isNull()).isTrue();
        assertThat(max.get("requests")).isEmpty();
        assertThat(max.get("measuredRequests").asLong()).isZero();
        assertThat(json(get("/api/executions/" + empty.getId() + "/report"), null).get("statistics").get("maxResponseTime").isNull()).isTrue();

        int status = mockMvc.perform(get("/api/executions/" + empty.getId() + "/extremes").param("kind", "MOYEN").with(AS_ADMIN))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(400);
        assertThat(mockMvc.perform(get("/api/executions/" + UUID.randomUUID() + "/extremes").with(AS_ADMIN))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }
}
