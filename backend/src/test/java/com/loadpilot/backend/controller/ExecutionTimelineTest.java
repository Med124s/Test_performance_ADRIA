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

/**
 * Analyse dans le temps sur une vraie execution : chronologie enregistree (VU actifs), series par
 * etape, erreurs par type, points cles ; tableau des erreurs du rapport regroupe.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExecutionTimelineTest {

    private static HttpServer server;
    private static String targetUrl;
    private static final AtomicInteger CALLS = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockBean
    private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_VIEWER = jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"));
    private static final RequestPostProcessor AS_ENGINEER = jwt().authorities(new SimpleGrantedAuthority("ROLE_PERFORMANCE_ENGINEER"));

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", ex -> {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            boolean fail = ex.getRequestURI().getPath().equals("/write") && CALLS.incrementAndGet() % 3 == 0;
            byte[] bytes = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(fail ? 409 : 200, bytes.length);
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

    private JsonNode call(MockHttpServletRequestBuilder builder, Object body, RequestPostProcessor as) throws Exception {
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        String json = mockMvc.perform(builder.with(as)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.isBlank() ? null : objectMapper.readTree(json);
    }

    @Test
    void recordedTimeline_perStepSeries_errorTypes_insights_andGroupedErrors() throws Exception {
        String appId = call(post("/api/applications"), Map.of("name", "timeline-" + UUID.randomUUID(), "url", targetUrl), AS_ENGINEER)
                .get("id").asText();
        String scenarioId = call(post("/api/scenarios"), Map.of("applicationId", appId, "name", "Chronologie", "virtualUsers", 3,
                "rampUpSeconds", 2, "durationSeconds", 4, "thinkTimeMs", 0), AS_ENGINEER).get("id").asText();
        call(post("/api/steps"), Map.of("scenarioId", scenarioId, "name", "Lire", "method", "GET", "url", "/read", "order", 1), AS_ENGINEER);
        call(post("/api/steps"), Map.of("scenarioId", scenarioId, "name", "Ecrire", "method", "POST", "url", "/write", "order", 2,
                "expectedStatus", 200), AS_ENGINEER);
        String executionId = call(post("/api/executions"), Map.of("scenarioId", scenarioId), AS_ENGINEER).get("id").asText();
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline
                && !List.of("SUCCESS", "FAILED").contains(call(get("/api/executions/" + executionId + "/status"), null, AS_VIEWER)
                        .get("status").asText())) {
            Thread.sleep(150);
        }
        Thread.sleep(600);

        JsonNode tl = call(get("/api/executions/" + executionId + "/timeline"), null, AS_VIEWER);
        assertThat(tl.get("source").asText()).isEqualTo("RECORDED");
        assertThat(tl.get("virtualUsersAvailable").asBoolean()).isTrue();
        assertThat(tl.get("bucketSeconds").asInt()).isEqualTo(1);
        int maxVu = 0;
        for (JsonNode v : tl.get("activeVirtualUsers")) maxVu = Math.max(maxVu, v.asInt());
        assertThat(maxVu).isEqualTo(3);
        assertThat(tl.get("activeVirtualUsers").get(0).asInt()).as("ramp-up : pas encore tous les VU au debut").isLessThan(3);
        assertThat(tl.get("t").size()).isEqualTo(tl.get("throughput").size()).isEqualTo(tl.get("p95Ms").size());
        assertThat(tl.get("steps").findValuesAsText("name")).containsExactly("Lire", "Ecrire");
        assertThat(tl.get("errorTypes").get(0).asText()).isEqualTo("HTTP 409");
        assertThat(tl.get("errorPoints").size()).isPositive();
        assertThat(tl.get("insights").size()).isEqualTo(3);
        assertThat(tl.get("insights").get(2).get("text").asText()).contains("HTTP 409 sur Ecrire");

        // Rapport : erreurs regroupees (une ligne par code + etape + message), occurrences et premiere apparition.
        JsonNode report = call(get("/api/executions/" + executionId + "/report"), null, AS_VIEWER);
        JsonNode groups = report.get("errorGroups");
        assertThat(groups.size()).isEqualTo(1);
        assertThat(groups.get(0).get("httpStatus").asInt()).isEqualTo(409);
        assertThat(groups.get(0).get("stepName").asText()).isEqualTo("Ecrire");
        assertThat(groups.get(0).get("count").asInt()).isEqualTo(report.get("errors").size());
        assertThat(groups.get(0).get("firstAt").asText()).isNotBlank();

        // Deuxieme lecture : servie depuis le cache, identique.
        assertThat(call(get("/api/executions/" + executionId + "/timeline"), null, AS_VIEWER)).isEqualTo(tl);
    }
}
