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
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
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

/** GET /api/executions/{id}/status pendant une vraie execution : compteurs en direct, puis totaux finaux. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExecutionLiveStatusTest {

    private static HttpServer server;
    private static String targetUrl;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockBean
    private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_ENGINEER = jwt().authorities(new SimpleGrantedAuthority("ROLE_PERFORMANCE_ENGINEER"));

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", ex -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(ex.getRequestURI().getPath().equals("/fail") ? 500 : 200, bytes.length);
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

    @Test
    void statusExposesLiveCountersWhileRunning_thenFinalTotals() throws Exception {
        String appId = call(post("/api/applications"), Map.of("name", "live-app", "url", targetUrl)).get("id").asText();
        Map<String, Object> scenario = new HashMap<>(Map.of("applicationId", appId, "name", "Suivi en direct",
                "virtualUsers", 3, "rampUpSeconds", 1, "durationSeconds", 3, "thinkTimeMs", 0));
        String scenarioId = call(post("/api/scenarios"), scenario).get("id").asText();
        call(post("/api/steps"), Map.of("scenarioId", scenarioId, "name", "Lire", "method", "GET", "url", "/ok", "order", 1));
        call(post("/api/steps"), Map.of("scenarioId", scenarioId, "name", "Ecrire", "method", "GET", "url", "/ok", "order", 2));

        String executionId = call(post("/api/executions"), Map.of("scenarioId", scenarioId)).get("id").asText();

        JsonNode running = null;
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode s = call(get("/api/executions/" + executionId + "/status"), null);
            if ("RUNNING".equals(s.get("status").asText()) && s.hasNonNull("live") && s.get("live").get("requests").asLong() > 3) {
                running = s;
                break;
            }
            Thread.sleep(150);
        }
        assertThat(running).as("statut RUNNING avec compteurs en direct").isNotNull();
        JsonNode live = running.get("live");
        assertThat(running.get("scenarioName").asText()).isEqualTo("Suivi en direct");
        assertThat(running.get("durationSeconds").asInt()).isEqualTo(3);
        assertThat(running.get("progressPercent").asInt()).isBetween(0, 99);
        assertThat(running.get("totalRequests").asLong()).isEqualTo(live.get("requests").asLong());
        assertThat(live.get("activeVirtualUsers").asInt()).isBetween(1, 3);
        assertThat(live.get("throughputRps").asDouble()).isPositive();
        assertThat(live.get("avgResponseTimeMs").asLong()).isGreaterThanOrEqualTo(90);
        assertThat(live.get("steps")).hasSize(2);
        assertThat(live.get("steps").get(0).get("name").asText()).isEqualTo("Lire");

        JsonNode done;
        do {
            Thread.sleep(300);
            done = call(get("/api/executions/" + executionId + "/status"), null);
        } while ("RUNNING".equals(done.get("status").asText()) || "QUEUED".equals(done.get("status").asText()));
        assertThat(done.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(done.get("progressPercent").asInt()).isEqualTo(100);
        assertThat(done.get("live").isNull()).isTrue();
        assertThat(done.get("totalRequests").asLong()).isGreaterThanOrEqualTo(live.get("requests").asLong());
    }
}
