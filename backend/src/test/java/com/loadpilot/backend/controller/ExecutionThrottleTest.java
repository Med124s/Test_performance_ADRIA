package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.service.execution.ErrorRateBrake;
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
 * Ralentissement sur 429, de bout en bout : reglage du scenario (actif par defaut), et interaction
 * avec le frein automatique : des 429 respectes ne declenchent pas le frein, des 429 avec le
 * ralentissement desactive le declenchent, et le resultat final compte toujours les 429.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExecutionThrottleTest {

    private static HttpServer server;
    private static String targetUrl;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ExecutionRepository executionRepository;
    @MockBean
    private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_ENGINEER = jwt().jwt(j -> j.subject("throttle-engineer"))
            .authorities(new SimpleGrantedAuthority("ROLE_PERFORMANCE_ENGINEER"));

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", ex -> {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // /limited : toujours 429, nouvel essai possible tout de suite.
            boolean limited = ex.getRequestURI().getPath().equals("/limited");
            if (limited) ex.getResponseHeaders().add("Retry-After", "0");
            ex.sendResponseHeaders(limited ? 429 : 200, -1);
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

    private int statusOf(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/scenarios").with(AS_ENGINEER).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))).andReturn().getResponse().getStatus();
    }

    private String app() throws Exception {
        return call(post("/api/applications"), Map.of("name", "ralenti-" + UUID.randomUUID(), "url", targetUrl)).get("id").asText();
    }

    private String awaitEnd(String executionId) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        String status = "";
        while (System.currentTimeMillis() < deadline) {
            status = call(get("/api/executions/" + executionId + "/status"), null).get("status").asText();
            if (!List.of("QUEUED", "RUNNING", "PAUSED").contains(status)) return status;
            Thread.sleep(50);
        }
        return status;
    }

    @Test
    void thinkTimeVariation_fixedByDefault_savedClearedAndValidated() throws Exception {
        String appId = app();
        JsonNode created = call(post("/api/scenarios"), Map.of("applicationId", appId, "name", "Think time"));
        assertThat(created.get("thinkTimeVariationPercent").isNull()).as("fixe par defaut").isTrue();
        String id = created.get("id").asText();
        assertThat(call(put("/api/scenarios/" + id), Map.of("applicationId", appId, "name", "Think time", "thinkTimeVariationPercent", 20))
                .get("thinkTimeVariationPercent").asInt()).isEqualTo(20);
        assertThat(call(put("/api/scenarios/" + id), Map.of("applicationId", appId, "name", "Renomme"))
                .get("thinkTimeVariationPercent").asInt()).as("champ absent : inchange").isEqualTo(20);
        assertThat(call(put("/api/scenarios/" + id), Map.of("applicationId", appId, "name", "Renomme", "thinkTimeVariationPercent", 0))
                .get("thinkTimeVariationPercent").isNull()).as("0 : fixe").isTrue();
        assertThat(statusOf(Map.of("applicationId", appId, "name", "x", "thinkTimeVariationPercent", 101))).isEqualTo(400);
        assertThat(statusOf(Map.of("applicationId", appId, "name", "x", "thinkTimeVariationPercent", -1))).isEqualTo(400);
    }

    @Test
    void throttleSetting_activeByDefault_savedKeptAndValidated() throws Exception {
        String appId = app();
        JsonNode created = call(post("/api/scenarios"), Map.of("applicationId", appId, "name", "Reglage 429"));
        // Actif par defaut : valeurs de Parametres > Systeme (initiales : actif, 5 s, 3 rejeux) ecrites dans le scenario.
        assertThat(created.get("throttleOn429").asBoolean()).as("actif par defaut").isTrue();
        assertThat(created.get("throttleDefaultDelaySeconds").asInt()).isEqualTo(5);
        assertThat(created.get("throttleMaxRetries").asInt()).isEqualTo(3);
        String id = created.get("id").asText();

        JsonNode off = call(put("/api/scenarios/" + id), Map.of("applicationId", appId, "name", "Reglage 429",
                "throttleOn429", false, "throttleDefaultDelaySeconds", 10, "throttleMaxRetries", 0));
        assertThat(off.get("throttleOn429").asBoolean()).isFalse();
        assertThat(off.get("throttleDefaultDelaySeconds").asInt()).isEqualTo(10);
        assertThat(off.get("throttleMaxRetries").asInt()).isZero();
        JsonNode kept = call(put("/api/scenarios/" + id), Map.of("applicationId", appId, "name", "Renomme"));
        assertThat(kept.get("throttleOn429").asBoolean()).as("champ absent : inchange").isFalse();

        assertThat(statusOf(Map.of("applicationId", appId, "name", "x", "throttleDefaultDelaySeconds", 0))).isEqualTo(400);
        assertThat(statusOf(Map.of("applicationId", appId, "name", "x", "throttleDefaultDelaySeconds", 301))).isEqualTo(400);
        assertThat(statusOf(Map.of("applicationId", appId, "name", "x", "throttleMaxRetries", 11))).isEqualTo(400);
    }

    private String scenarioOn429(String appId, Map<String, Object> extra) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("applicationId", appId, "name", "Mur de 429", "virtualUsers", 2,
                "rampUpSeconds", 0, "thinkTimeMs", 0, "durationSeconds", 4,
                "errorBrakeRatePercent", 10, "errorBrakeWindowSeconds", 5));
        body.putAll(extra);
        String id = call(post("/api/scenarios"), body).get("id").asText();
        call(post("/api/steps"), Map.of("scenarioId", id, "name", "Appel", "method", "GET", "url", "/limited", "order", 1));
        return id;
    }

    @Test
    void respected429s_doNotTripTheBrake_butDisabledThrottlingDoes() throws Exception {
        String appId = app();

        // Ralentissement actif (defaut) : la cible ne renvoie que des 429, le frein a 10 % ne se declenche pas ;
        // le test va au bout et le resultat final compte les 429 (Seuils depasses).
        String respected = call(post("/api/executions"), Map.of("scenarioId", scenarioOn429(appId, Map.of()))).get("id").asText();
        assertThat(awaitEnd(respected)).isEqualTo("THRESHOLDS_EXCEEDED");

        // Ralentissement desactive (test de resilience) : les 429 sont des erreurs ordinaires, le frein arrete le test.
        String wall = call(post("/api/executions"), Map.of("scenarioId", scenarioOn429(appId, Map.of("throttleOn429", false))))
                .get("id").asText();
        assertThat(awaitEnd(wall)).isEqualTo("CANCELLED");
        assertThat(executionRepository.findById(UUID.fromString(wall)).orElseThrow().getErrorMessage())
                .startsWith(ErrorRateBrake.REASON_PREFIX);
    }
}
