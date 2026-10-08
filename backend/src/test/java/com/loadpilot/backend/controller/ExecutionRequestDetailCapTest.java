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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
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

/** Plafond memoire du detail des requetes atteint : echantillon arrete, echecs conserves, signale. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "app.execution.request-detail.max-memory-mb=0")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExecutionRequestDetailCapTest {

    private static HttpServer server;
    private static String targetUrl;
    private static final AtomicInteger CALLS = new AtomicInteger();

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
            byte[] bytes = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(CALLS.incrementAndGet() % 2 == 0 ? 500 : 200, bytes.length);
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
    void capReached_sampleStopped_failuresStillRecorded_andFlagged() throws Exception {
        String appId = call(post("/api/applications"), Map.of("name", "cap-" + UUID.randomUUID(), "url", targetUrl)).get("id").asText();
        String scenarioId = call(post("/api/scenarios"), Map.of("applicationId", appId, "name", "Plafond", "virtualUsers", 1,
                "rampUpSeconds", 0, "iterations", 6, "thinkTimeMs", 0)).get("id").asText();
        call(post("/api/steps"), Map.of("scenarioId", scenarioId, "name", "Appel", "method", "GET", "url", "/x", "order", 1));
        String executionId = call(post("/api/executions"), Map.of("scenarioId", scenarioId)).get("id").asText();
        long deadline = System.currentTimeMillis() + 15_000;
        JsonNode summary;
        do {
            Thread.sleep(150);
            summary = call(get("/api/executions/" + executionId + "?includeResults=false"), null);
        } while (System.currentTimeMillis() < deadline && !List.of("SUCCESS", "THRESHOLDS_EXCEEDED", "FAILED", "CANCELLED").contains(summary.get("status").asText()));
        Thread.sleep(300);
        summary = call(get("/api/executions/" + executionId + "?includeResults=false"), null);

        assertThat(summary.get("detailSampleCapReached").asBoolean()).isTrue();
        assertThat(summary.get("detailedSamples").asLong()).isZero();
        assertThat(summary.get("detailedFailures").asLong()).isEqualTo(3);
    }
}
