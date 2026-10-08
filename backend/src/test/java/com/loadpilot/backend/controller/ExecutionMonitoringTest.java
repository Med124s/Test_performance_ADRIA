package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Monitoring CPU / RAM de l'application testee, de bout en bout : configuration (sans jamais
 * renvoyer le secret), « Tester le monitoring », lecture pendant l'execution, colonnes de la liste,
 * courbe de l'analyse dans le temps, et monitoring indisponible sans impact sur l'execution.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExecutionMonitoringTest {

    private static HttpServer server;
    private static String targetUrl;
    private static final AtomicLong requests = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockBean
    private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_ADMIN = jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/metrics", ex -> {
            // CPU cumule qui avance avec le nombre de requetes recues ; RSS fixe a 150 Mo.
            String body = "# TYPE process_cpu_seconds_total counter\nprocess_cpu_seconds_total " + (requests.get() * 0.002)
                    + "\nprocess_resident_memory_bytes " + (150L * 1024 * 1024) + "\n";
            reply(ex, 200, body);
        });
        server.createContext("/", ex -> {
            requests.incrementAndGet();
            reply(ex, 200, "{\"success\":true}");
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        targetUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private MvcResult perform(MockHttpServletRequestBuilder builder, Object body) throws Exception {
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        return mockMvc.perform(builder.with(AS_ADMIN)).andReturn();
    }

    private JsonNode json(MvcResult r) throws Exception {
        String s = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return s.isBlank() ? null : objectMapper.readTree(s);
    }

    private static Map<String, Object> monitoring(String url, String authType, String secret) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", "PROMETHEUS");
        m.put("url", url);
        m.put("authType", authType);
        m.put("secret", secret);
        m.put("intervalSeconds", 1);
        return m;
    }

    /** Cree application + scenario (2 VU, 4 s) et lance l'execution ; renvoie l'id de l'execution une fois terminee. */
    private String runWithMonitoring(Map<String, Object> monitoring) throws Exception {
        MvcResult created = perform(post("/api/applications"), Map.of("name", "monitoring-" + UUID.randomUUID(), "url", targetUrl,
                "monitoring", monitoring));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String appId = json(created).get("id").asText();
        String scenarioId = json(perform(post("/api/scenarios"), Map.of("applicationId", appId, "name", "Monitoring",
                "virtualUsers", 2, "rampUpSeconds", 0, "durationSeconds", 4, "thinkTimeMs", 50))).get("id").asText();
        perform(post("/api/steps"), Map.of("scenarioId", scenarioId, "name", "Lire", "method", "GET", "url", "/lire", "order", 1,
                "expectedStatus", 200, "assertionBodyContains", "\"success\":true"));
        String executionId = json(perform(post("/api/executions"), Map.of("scenarioId", scenarioId))).get("id").asText();
        long deadline = System.currentTimeMillis() + 30_000;
        JsonNode tl = null;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(300);
            String status = json(perform(get("/api/executions/" + executionId + "/status"), null)).get("status").asText();
            if (!List.of("SUCCESS", "THRESHOLDS_EXCEEDED", "FAILED").contains(status)) continue;
            tl = json(perform(get("/api/executions/" + executionId + "/timeline"), null));
            if ("RECORDED".equals(tl.get("source").asText())) break;
        }
        assertThat(tl).isNotNull();
        return executionId;
    }

    @Test
    void monitoringIsRead_duringTheRun_andShownInTheListAndTheTimeline_withoutEverExposingTheSecret() throws Exception {
        String executionId = runWithMonitoring(monitoring(targetUrl + "/metrics", "BEARER", "jeton-tres-secret"));

        JsonNode status = json(perform(get("/api/executions/" + executionId + "/status"), null));
        assertThat(status.get("status").asText()).isEqualTo("SUCCESS");
        JsonNode resources = status.get("resources");
        assertThat(resources.get("memoryMaxMb").asDouble()).isEqualTo(150.0);
        assertThat(resources.get("cpuMaxPercent").isNull()).isFalse();
        assertThat(resources.get("message").isNull()).isTrue();

        JsonNode page = json(perform(get("/api/executions/page").param("size", "100"), null));
        JsonNode row = null;
        for (JsonNode r : page.get("content")) if (r.get("id").asText().equals(executionId)) row = r;
        assertThat(row).isNotNull();
        assertThat(row.get("memoryMaxMb").asDouble()).isEqualTo(150.0);
        assertThat(row.get("cpuAvgPercent").isNull()).isFalse();

        JsonNode tl = json(perform(get("/api/executions/" + executionId + "/timeline"), null));
        JsonNode series = tl.get("resources");
        assertThat(series.get("source").asText()).isEqualTo("PROMETHEUS");
        assertThat(series.get("memoryMetric").asText()).isEqualTo("process_resident_memory_bytes");
        assertThat(series.get("sampleCount").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(series.get("memoryMb").size()).isEqualTo(tl.get("t").size());
        boolean has150 = false;
        for (JsonNode v : series.get("memoryMb")) has150 |= !v.isNull() && v.asDouble() == 150.0;
        assertThat(has150).isTrue();

        JsonNode apps = json(perform(get("/api/applications"), null));
        assertThat(apps.toString()).doesNotContain("jeton-tres-secret");
    }

    @Test
    void unavailableMonitoring_theRunContinuesNormally_andAClearMessageIsShown() throws Exception {
        int closed;
        try (ServerSocket s = new ServerSocket(0)) {
            closed = s.getLocalPort();
        }
        String executionId = runWithMonitoring(monitoring("http://localhost:" + closed + "/metrics", "NONE", null));

        JsonNode status = json(perform(get("/api/executions/" + executionId + "/status"), null));
        assertThat(status.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(status.get("successfulRequests").asInt()).isPositive();
        assertThat(status.get("resources").get("message").asText())
                .startsWith("Monitoring indisponible : Monitoring injoignable (connexion refusée)")
                .endsWith("L'exécution a continué normalement, sans CPU ni RAM.");
        JsonNode tl = json(perform(get("/api/executions/" + executionId + "/timeline"), null));
        assertThat(tl.get("resources").get("message").asText()).startsWith("Monitoring indisponible");
        assertThat(tl.get("resources").get("sampleCount").asInt()).isZero();
    }

    @Test
    void configuration_isValidated_andTheTestButtonReadsRealValues() throws Exception {
        MvcResult missingUrl = perform(post("/api/applications"), Map.of("name", "sans-url-" + UUID.randomUUID(), "url", targetUrl,
                "monitoring", Map.of("type", "PROMETHEUS")));
        assertThat(missingUrl.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(missingUrl).get("message").asText()).isEqualTo("Indiquez l'URL du monitoring (ex : http://localhost:8888/metrics).");

        JsonNode test = json(perform(post("/api/applications/monitoring/test"),
                Map.of("monitoring", monitoring(targetUrl + "/metrics", "NONE", null))));
        assertThat(test.get("success").asBoolean()).isTrue();
        assertThat(test.get("memoryMb").asDouble()).isEqualTo(150.0);
        assertThat(test.get("message").asText()).startsWith("Monitoring opérationnel : CPU ");

        JsonNode none = json(perform(post("/api/applications"), Map.of("name", "aucun-" + UUID.randomUUID(), "url", targetUrl)));
        assertThat(none.get("monitoring").get("type").asText()).isEqualTo("NONE");
        assertThat(none.get("monitoring").get("intervalSeconds").asInt()).isEqualTo(5);
    }
}
