package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.ScenarioRepository;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Seuils de reussite par scenario, de bout en bout : enregistrement sur le scenario, copie a l'execution,
 * statut final (Reussie / Seuils depasses / Echouee), raison du statut dans la liste, ecart de P95 avec
 * l'execution precedente, resultat des seuils dans le rapport, annulation « par … », et anciennes
 * executions qui gardent leur statut.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExecutionThresholdsTest {

    private static HttpServer server;
    private static String targetUrl;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ExecutionRepository executionRepository;
    @Autowired
    private ScenarioRepository scenarioRepository;
    @MockBean
    private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_ENGINEER = jwt().jwt(j -> j.subject("thr-engineer"))
            .authorities(new SimpleGrantedAuthority("ROLE_PERFORMANCE_ENGINEER"));

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            try {
                Thread.sleep(path.equals("/slow") ? 40 : path.equals("/long") ? 300 : 2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(path.equals("/fail") ? 500 : 200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        targetUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private JsonNode call(MockHttpServletRequestBuilder builder, Object body) throws Exception {
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        String json = mockMvc.perform(builder.with(AS_ENGINEER)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.isBlank() ? null : objectMapper.readTree(json);
    }

    private String app() throws Exception {
        return call(post("/api/applications"), Map.of("name", "seuils-" + UUID.randomUUID(), "url", targetUrl)).get("id").asText();
    }

    private JsonNode scenario(String appId, String name, String path, Map<String, Object> extra, Integer durationSeconds) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("applicationId", appId, "name", name, "virtualUsers", 1, "rampUpSeconds", 0, "thinkTimeMs", 0));
        if (durationSeconds != null) body.put("durationSeconds", durationSeconds);
        else body.put("iterations", 5);
        body.putAll(extra);
        JsonNode created = call(post("/api/scenarios"), body);
        call(post("/api/steps"), Map.of("scenarioId", created.get("id").asText(), "name", "Appel", "method", "GET", "url", path, "order", 1));
        return created;
    }

    private String runToEnd(String scenarioId) throws Exception {
        String executionId = call(post("/api/executions"), Map.of("scenarioId", scenarioId)).get("id").asText();
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            String status = call(get("/api/executions/" + executionId + "/status"), null).get("status").asText();
            if (!List.of("QUEUED", "RUNNING", "PAUSED").contains(status)) break;
            Thread.sleep(50);
        }
        Thread.sleep(400);
        return executionId;
    }

    private JsonNode row(String appId, String executionId) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        JsonNode found = null;
        while (System.currentTimeMillis() < deadline) {
            for (JsonNode r : call(get("/api/executions/page").param("applicationId", appId).param("size", "50"), null).get("content")) {
                if (r.get("id").asText().equals(executionId)) found = r;
            }
            if (found != null && !found.get("p95Ms").isNull()) return found;
            Thread.sleep(150);
        }
        return found;
    }

    @Test
    void scenarioThresholds_areSaved_withDefaultsAndClearing() throws Exception {
        String appId = app();
        JsonNode defaults = scenario(appId, "Seuils par defaut", "/ok", Map.of(), null);
        assertThat(defaults.get("maxErrorRatePercent").asDouble()).isEqualTo(1.0);
        assertThat(defaults.get("maxP95Ms").isNull()).isTrue();

        String id = defaults.get("id").asText();
        JsonNode updated = call(put("/api/scenarios/" + id), Map.of("applicationId", appId, "name", "Seuils par defaut",
                "maxErrorRatePercent", 2.5, "maxP95Ms", 800));
        assertThat(updated.get("maxErrorRatePercent").asDouble()).isEqualTo(2.5);
        assertThat(updated.get("maxP95Ms").asLong()).isEqualTo(800);
        // Champs absents : inchanges ; P95 maximal 0 : seuil retire.
        JsonNode kept = call(put("/api/scenarios/" + id), Map.of("applicationId", appId, "name", "Renomme"));
        assertThat(kept.get("maxErrorRatePercent").asDouble()).isEqualTo(2.5);
        assertThat(kept.get("maxP95Ms").asLong()).isEqualTo(800);
        JsonNode cleared = call(put("/api/scenarios/" + id), Map.of("applicationId", appId, "name", "Renomme", "maxP95Ms", 0));
        assertThat(cleared.get("maxP95Ms").isNull()).isTrue();

        assertThat(mockMvc.perform(post("/api/scenarios").with(AS_ENGINEER).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("applicationId", appId, "name", "x", "maxErrorRatePercent", 150))))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void finalStatus_followsThresholds_withReasonReportAndP95Gap() throws Exception {
        String appId = app();

        // Taux d'erreurs 100 % mais maximum 100 % : seuil respecte -> Reussie.
        String tolerant = scenario(appId, "Tolerant", "/fail", Map.of("maxErrorRatePercent", 100), null).get("id").asText();
        String tolerantRun = runToEnd(tolerant);
        assertThat(row(appId, tolerantRun).get("status").asText()).isEqualTo("SUCCESS");

        // Erreurs au-dela du maximum (defaut 1 %) -> Seuils depasses, raison « Erreurs : 100 % ».
        String errors = scenario(appId, "Erreurs", "/fail", Map.of(), null).get("id").asText();
        JsonNode errorsRow = row(appId, runToEnd(errors));
        assertThat(errorsRow.get("status").asText()).isEqualTo("THRESHOLDS_EXCEEDED");
        assertThat(errorsRow.get("statusReason").asText()).isEqualTo("Erreurs : 100 %");
        assertThat(errorsRow.get("maxErrorRatePercent").asDouble()).isEqualTo(1.0);

        // P95 au-dela du maximum -> Seuils depasses, raison « P95 > seuil » ; rapport : ✓ erreurs, ✕ P95.
        String slow = scenario(appId, "Lent", "/slow", Map.of("maxP95Ms", 10), null).get("id").asText();
        String slowRun = runToEnd(slow);
        JsonNode slowRow = row(appId, slowRun);
        assertThat(slowRow.get("status").asText()).isEqualTo("THRESHOLDS_EXCEEDED");
        assertThat(slowRow.get("statusReason").asText()).isEqualTo("P95 > seuil");
        JsonNode thresholds = call(get("/api/executions/" + slowRun + "/report"), null).get("thresholds");
        assertThat(thresholds.get("errorRateOk").asBoolean()).isTrue();
        assertThat(thresholds.get("maxP95Ms").asLong()).isEqualTo(10);
        assertThat(thresholds.get("p95Ok").asBoolean()).isFalse();
        assertThat(thresholds.get("p95Ms").asLong()).isGreaterThan(10);

        // Les seuils sont copies au lancement : modifier le scenario ne change pas l'execution passee.
        call(put("/api/scenarios/" + slow), Map.of("applicationId", appId, "name", "Lent", "maxP95Ms", 5000));
        assertThat(call(get("/api/executions/" + slowRun + "/report"), null).get("thresholds").get("maxP95Ms").asLong()).isEqualTo(10);

        // Deuxieme execution du meme scenario : P95 de la precedente et sa date dans la liste.
        String secondRun = runToEnd(slow);
        JsonNode second = row(appId, secondRun);
        assertThat(second.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(second.get("statusReason").isNull()).isTrue();
        assertThat(second.get("previousP95Ms").asLong()).isEqualTo(slowRow.get("p95Ms").asLong());
        assertThat(Instant.parse(second.get("previousStartedAt").asText())).isBefore(Instant.parse(second.get("startedAt").asText()));
        assertThat(row(appId, tolerantRun).get("previousP95Ms").isNull()).as("premiere execution du scenario").isTrue();
    }

    @Test
    void failedOnlyWhenTheTestCouldNotRun_cancelledByUser_andOldExecutionsKeepTheirStatus() throws Exception {
        // Application injoignable : aucune reponse -> Echouee, raison « Application injoignable ».
        String downApp = call(post("/api/applications"), Map.of("name", "injoignable-" + UUID.randomUUID(), "url", "http://localhost:1"))
                .get("id").asText();
        String down = scenario(downApp, "Injoignable", "/ok", Map.of(), null).get("id").asText();
        String downRun = runToEnd(down);
        JsonNode downRow = call(get("/api/executions/page").param("applicationId", downApp), null).get("content").get(0);
        assertThat(downRow.get("id").asText()).isEqualTo(downRun);
        assertThat(downRow.get("status").asText()).isEqualTo("FAILED");
        assertThat(downRow.get("statusReason").asText()).isEqualTo("Application injoignable");

        // Annulation par un utilisateur : « Annulée par thr-engineer ».
        String appId = app();
        String longScenario = scenario(appId, "Long", "/long", Map.of(), 30).get("id").asText();
        String running = call(post("/api/executions"), Map.of("scenarioId", longScenario)).get("id").asText();
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline
                && !"RUNNING".equals(call(get("/api/executions/" + running + "/status"), null).get("status").asText())) Thread.sleep(50);
        call(post("/api/executions/" + running + "/cancel"), null);
        deadline = System.currentTimeMillis() + 10_000;
        String status = "";
        while (System.currentTimeMillis() < deadline
                && !(status = call(get("/api/executions/" + running + "/status"), null).get("status").asText()).equals("CANCELLED")) Thread.sleep(50);
        assertThat(status).isEqualTo("CANCELLED");
        JsonNode cancelled = call(get("/api/executions/page").param("applicationId", appId), null).get("content").get(0);
        assertThat(cancelled.get("statusReason").asText()).isEqualTo("Annulée par thr-engineer");

        // Execution anterieure aux seuils (aucune copie) : statut d'origine garde, raison = taux d'erreurs.
        Execution old = executionRepository.saveAndFlush(Execution.builder()
                .scenario(scenarioRepository.findById(UUID.fromString(longScenario)).orElseThrow())
                .startedAt(Instant.now().minusSeconds(3600)).finishedAt(Instant.now().minusSeconds(3500)).duration(100_000L)
                .status(ExecutionStatus.FAILED).errorMessage("HTTP 500").totalSteps(1).successfulSteps(39).failedSteps(1).build());
        JsonNode oldRow = null;
        for (JsonNode r : call(get("/api/executions/page").param("applicationId", appId), null).get("content")) {
            if (r.get("id").asText().equals(old.getId().toString())) oldRow = r;
        }
        assertThat(oldRow.get("status").asText()).isEqualTo("FAILED");
        assertThat(oldRow.get("statusReason").asText()).isEqualTo("Erreurs : 2,5 %");
        assertThat(oldRow.get("maxErrorRatePercent").isNull()).isTrue();
        assertThat(call(get("/api/executions/" + old.getId() + "/report"), null).get("thresholds").isNull()).isTrue();
    }
}
