package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.service.execution.ErrorRateBrake;
import com.loadpilot.backend.service.impl.ExecutionServiceImpl;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
 * Frein automatique sur taux d'erreurs, de bout en bout : reglage du scenario, arret d'une
 * execution qui depasse le seuil (statut Annulee, motif distinct), aucun arret quand le frein est
 * absent / desactive / non atteint, et annulation manuelle simultanee (la premiere l'emporte).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExecutionErrorBrakeTest {

    private static HttpServer server;
    private static String targetUrl;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ExecutionRepository executionRepository;
    @Autowired
    private ExecutionServiceImpl executionService;
    @MockBean
    private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_ENGINEER = jwt().jwt(j -> j.subject("brake-engineer"))
            .authorities(new SimpleGrantedAuthority("ROLE_PERFORMANCE_ENGINEER"));

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            try {
                Thread.sleep(path.equals("/long") ? 300 : 5);
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

    private int statusOf(MockHttpServletRequestBuilder builder, Object body) throws Exception {
        return mockMvc.perform(builder.with(AS_ENGINEER).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))).andReturn().getResponse().getStatus();
    }

    private String app() throws Exception {
        return call(post("/api/applications"), Map.of("name", "frein-" + UUID.randomUUID(), "url", targetUrl)).get("id").asText();
    }

    private String scenario(String appId, String path, Map<String, Object> extra, Integer durationSeconds) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("applicationId", appId, "name", "Frein " + path, "virtualUsers", 2,
                "rampUpSeconds", 0, "thinkTimeMs", 0));
        if (durationSeconds != null) body.put("durationSeconds", durationSeconds);
        else body.put("iterations", 30);
        body.putAll(extra);
        String id = call(post("/api/scenarios"), body).get("id").asText();
        call(post("/api/steps"), Map.of("scenarioId", id, "name", "Appel", "method", "GET", "url", path, "order", 1));
        return id;
    }

    private String launch(String scenarioId) throws Exception {
        return call(post("/api/executions"), Map.of("scenarioId", scenarioId)).get("id").asText();
    }

    private String awaitStatus(String executionId, List<String> wanted, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String status = "";
        while (System.currentTimeMillis() < deadline) {
            status = call(get("/api/executions/" + executionId + "/status"), null).get("status").asText();
            if (wanted.contains(status)) return status;
            Thread.sleep(50);
        }
        return status;
    }

    private String awaitEnd(String executionId) throws Exception {
        return awaitStatus(executionId, List.of("SUCCESS", "THRESHOLDS_EXCEEDED", "FAILED", "CANCELLED"), 20_000);
    }

    private Execution execution(String id) {
        return executionRepository.findById(UUID.fromString(id)).orElseThrow();
    }

    private JsonNode row(String appId, String executionId) throws Exception {
        for (JsonNode r : call(get("/api/executions/page").param("applicationId", appId).param("size", "50"), null).get("content")) {
            if (r.get("id").asText().equals(executionId)) return r;
        }
        return null;
    }

    @Test
    void brakeSetting_isDisabledByDefault_savedKeptAndCleared_andValidated() throws Exception {
        String appId = app();
        JsonNode created = call(post("/api/scenarios"), Map.of("applicationId", appId, "name", "Reglage"));
        assertThat(created.get("errorBrakeRatePercent").isNull()).as("desactive par defaut").isTrue();
        String id = created.get("id").asText();

        JsonNode set = call(put("/api/scenarios/" + id), Map.of("applicationId", appId, "name", "Reglage",
                "errorBrakeRatePercent", 25, "errorBrakeWindowSeconds", 60));
        assertThat(set.get("errorBrakeRatePercent").asDouble()).isEqualTo(25.0);
        assertThat(set.get("errorBrakeWindowSeconds").asInt()).isEqualTo(60);
        JsonNode kept = call(put("/api/scenarios/" + id), Map.of("applicationId", appId, "name", "Renomme"));
        assertThat(kept.get("errorBrakeRatePercent").asDouble()).as("champ absent : inchange").isEqualTo(25.0);
        JsonNode cleared = call(put("/api/scenarios/" + id), Map.of("applicationId", appId, "name", "Renomme", "errorBrakeRatePercent", 0));
        assertThat(cleared.get("errorBrakeRatePercent").isNull()).as("0 : desactive").isTrue();

        assertThat(statusOf(post("/api/scenarios"), Map.of("applicationId", appId, "name", "x", "errorBrakeRatePercent", 150))).isEqualTo(400);
        assertThat(statusOf(post("/api/scenarios"), Map.of("applicationId", appId, "name", "x", "errorBrakeRatePercent", 10,
                "errorBrakeWindowSeconds", 2))).isEqualTo(400);
        assertThat(statusOf(post("/api/scenarios"), Map.of("applicationId", appId, "name", "x", "errorBrakeRatePercent", 10,
                "errorBrakeWindowSeconds", 301))).isEqualTo(400);
    }

    @Test
    void brakeTrips_whenTheErrorRateExceedsTheThreshold_withADistinctMessage() throws Exception {
        String appId = app();
        // 100 % d'erreurs pendant 30 s prevues ; frein a 50 % sur 5 s : arret bien avant la fin.
        String scenarioId = scenario(appId, "/fail", Map.of("errorBrakeRatePercent", 50, "errorBrakeWindowSeconds", 5), 30);
        long startedAt = System.currentTimeMillis();
        String executionId = launch(scenarioId);
        assertThat(awaitEnd(executionId)).isEqualTo("CANCELLED");
        assertThat(System.currentTimeMillis() - startedAt).as("arrete avant la duree prevue").isLessThan(15_000);

        Execution e = execution(executionId);
        assertThat(e.getErrorMessage()).startsWith(ErrorRateBrake.REASON_PREFIX)
                .contains("100 % d'erreurs sur les 5 dernières secondes").contains("au-delà du seuil de 50 %");
        assertThat(e.getCancelledBy()).as("pas une annulation manuelle").isNull();
        assertThat(row(appId, executionId).get("statusReason").asText()).isEqualTo("Arrêt automatique : trop d’erreurs");
    }

    @Test
    void brakeNeverTrips_whenAbsent_disabled_orNotReached() throws Exception {
        String appId = app();
        // Sans frein / frein a 0 : le test va au bout, jugé par les seuils de reussite (Seuils depasses).
        String absent = launch(scenario(appId, "/fail", Map.of(), null));
        String disabled = launch(scenario(appId, "/fail", Map.of("errorBrakeRatePercent", 0), null));
        // Frein actif mais aucune erreur : Reussie.
        String notReached = launch(scenario(appId, "/ok", Map.of("errorBrakeRatePercent", 5, "errorBrakeWindowSeconds", 5), 3));
        assertThat(awaitEnd(absent)).isEqualTo("THRESHOLDS_EXCEEDED");
        assertThat(awaitEnd(disabled)).isEqualTo("THRESHOLDS_EXCEEDED");
        assertThat(awaitEnd(notReached)).isEqualTo("SUCCESS");
        assertThat(execution(absent).getErrorMessage()).startsWith("Seuils dépassés");
    }

    @Test
    void manualCancelAndBrake_atTheSameTime_firstOneWins_singleFinalState() throws Exception {
        String appId = app();
        String scenarioId = scenario(appId, "/long", Map.of(), 30);

        // Annulation manuelle d'abord, frein juste apres : « Annulée par … » garde.
        String manualFirst = launch(scenarioId);
        assertThat(awaitStatus(manualFirst, List.of("RUNNING"), 5000)).isEqualTo("RUNNING");
        call(post("/api/executions/" + manualFirst + "/cancel"), null);
        executionService.cancelForErrorRate(UUID.fromString(manualFirst), ErrorRateBrake.REASON_PREFIX + "test.");
        assertThat(awaitEnd(manualFirst)).isEqualTo("CANCELLED");
        Execution manual = execution(manualFirst);
        assertThat(manual.getCancelledBy()).isEqualTo("brake-engineer");
        assertThat(manual.getErrorMessage()).doesNotStartWith(ErrorRateBrake.REASON_PREFIX);

        // Frein d'abord, clic sur « Annuler » juste apres : le motif du frein est garde.
        String brakeFirst = launch(scenarioId);
        assertThat(awaitStatus(brakeFirst, List.of("RUNNING"), 5000)).isEqualTo("RUNNING");
        executionService.cancelForErrorRate(UUID.fromString(brakeFirst), ErrorRateBrake.REASON_PREFIX + "test.");
        call(post("/api/executions/" + brakeFirst + "/cancel"), null);
        assertThat(awaitEnd(brakeFirst)).isEqualTo("CANCELLED");
        Execution brake = execution(brakeFirst);
        assertThat(brake.getErrorMessage()).isEqualTo(ErrorRateBrake.REASON_PREFIX + "test.");
        assertThat(brake.getCancelledBy()).isNull();

        // Frein apres la fin : rien ne change.
        executionService.cancelForErrorRate(UUID.fromString(brakeFirst), ErrorRateBrake.REASON_PREFIX + "tardif.");
        assertThat(execution(brakeFirst).getErrorMessage()).isEqualTo(ErrorRateBrake.REASON_PREFIX + "test.");
    }
}
