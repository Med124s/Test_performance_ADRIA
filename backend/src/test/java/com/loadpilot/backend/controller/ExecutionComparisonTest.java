package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.ScenarioRepository;
import com.loadpilot.backend.service.execution.RunningExecutionRegistry;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
 * « Comparer des executions » (memes valeurs que les rapports, P95 par etape, avertissement si
 * scenarios differents, validations) et filtre par etape de l'evolution des performances.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExecutionComparisonTest {

    private static HttpServer server;
    private static String targetUrl;
    private static final AtomicInteger writes = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ExecutionRepository executionRepository;
    @Autowired
    private ScenarioRepository scenarioRepository;
    @Autowired
    private RunningExecutionRegistry registry;
    @MockBean
    private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_ADMIN = jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", ex -> {
            // « Ecrire » echoue une fois sur quatre (409), « Lire » reussit toujours.
            boolean ko = ex.getRequestURI().getPath().startsWith("/ecrire") && writes.incrementAndGet() % 4 == 0;
            byte[] bytes = (ko ? "{\"success\":false}" : "{\"success\":true}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(ko ? 409 : 200, bytes.length);
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

    private JsonNode json(MockHttpServletRequestBuilder builder, Object body) throws Exception {
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        String s = mockMvc.perform(builder.with(AS_ADMIN)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return s.isBlank() ? null : objectMapper.readTree(s);
    }

    private int status(MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder.with(AS_ADMIN)).andReturn().getResponse().getStatus();
    }

    private String scenario(String appId, String name) throws Exception {
        String id = json(post("/api/scenarios"), Map.of("applicationId", appId, "name", name, "virtualUsers", 1, "rampUpSeconds", 0,
                "iterations", 8, "thinkTimeMs", 0)).get("id").asText();
        json(post("/api/steps"), Map.of("scenarioId", id, "name", "Lire", "method", "GET", "url", "/lire", "order", 1, "expectedStatus", 200));
        json(post("/api/steps"), Map.of("scenarioId", id, "name", "Ecrire", "method", "POST", "url", "/ecrire", "order", 2, "expectedStatus", 200));
        return id;
    }

    private String run(String scenarioId, int virtualUsers) throws Exception {
        JsonNode sc = json(get("/api/scenarios/" + scenarioId), null);
        json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/scenarios/" + scenarioId),
                Map.of("applicationId", sc.get("applicationId").asText(), "name", sc.get("name").asText(), "virtualUsers", virtualUsers,
                        "rampUpSeconds", 0, "iterations", 8, "thinkTimeMs", 0));
        String id = json(post("/api/executions"), Map.of("scenarioId", scenarioId)).get("id").asText();
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(150);
            String st = json(get("/api/executions/" + id + "/status"), null).get("status").asText();
            if (List.of("SUCCESS", "THRESHOLDS_EXCEEDED", "FAILED").contains(st) && registry.get(UUID.fromString(id)) == null) break;
        }
        return id;
    }

    @Test
    void compare_sameValuesAsReports_stepP95_warningForDifferentScenarios_andStepTrend() throws Exception {
        Instant start = Instant.now().minusSeconds(1);
        String appId = json(post("/api/applications"), Map.of("name", "compare-" + UUID.randomUUID(), "url", targetUrl)).get("id").asText();
        String s1 = scenario(appId, "Comparaison-" + UUID.randomUUID().toString().substring(0, 6));
        String run1 = run(s1, 1);
        String run2 = run(s1, 3);

        JsonNode c = json(get("/api/executions/compare").param("ids", run2 + "," + run1), null);
        assertThat(c.get("sameScenario").asBoolean()).isTrue();
        assertThat(c.get("warning").isNull()).isTrue();
        JsonNode cols = c.get("executions");
        assertThat(cols.get(0).get("executionId").asText()).as("ordre chronologique").isEqualTo(run1);
        assertThat(cols.get(1).get("virtualUsers").asInt()).isEqualTo(3);
        for (int i = 0; i < 2; i++) {
            JsonNode col = cols.get(i);
            JsonNode report = json(get("/api/executions/" + col.get("executionId").asText() + "/report"), null);
            JsonNode st = report.get("statistics");
            assertThat(col.get("requests").asInt()).isEqualTo(st.get("totalRequests").asInt());
            assertThat(col.get("p95Ms").asLong()).isEqualTo(st.get("p95").asLong());
            assertThat(col.get("p99Ms").asLong()).isEqualTo(st.get("p99").asLong());
            assertThat(col.get("throughput").decimalValue()).isEqualByComparingTo(st.get("throughput").decimalValue());
            assertThat(col.get("errorRate").decimalValue()).isEqualByComparingTo(st.get("errorRate").decimalValue());
            assertThat(col.get("rampUpSeconds").asInt()).isZero();
            JsonNode reportSteps = report.get("steps");
            assertThat(c.get("steps").size()).isEqualTo(2);
            for (int k = 0; k < reportSteps.size(); k++) {
                JsonNode row = c.get("steps").get(k);
                assertThat(row.get("name").asText()).isEqualTo(reportSteps.get(k).get("stepName").asText());
                assertThat(row.get("p95Ms").get(i).asLong()).isEqualTo(reportSteps.get(k).get("p95").asLong());
            }
        }

        // Scenario different : avertissement.
        String s2 = scenario(appId, "Autre-" + UUID.randomUUID().toString().substring(0, 6));
        String run3 = run(s2, 1);
        JsonNode mixed = json(get("/api/executions/compare").param("ids", run1, run3), null);
        assertThat(mixed.get("sameScenario").asBoolean()).isFalse();
        assertThat(mixed.get("warning").asText()).startsWith("Ces exécutions n'utilisent pas le même scénario");

        // Evolution des performances filtree sur l'etape « Ecrire » : P95 et taux d'erreurs de l'etape.
        JsonNode trend = json(get("/api/dashboard/scenario-trend").param("scenarioId", s1).param("from", start.toString())
                .param("stepName", "Ecrire"), null);
        assertThat(trend.get("steps").findValuesAsText("name")).containsExactly("Lire", "Ecrire");
        assertThat(trend.get("stepName").asText()).isEqualTo("Ecrire");
        JsonNode report1 = json(get("/api/executions/" + run1 + "/report"), null);
        JsonNode writeStep = report1.get("steps").get(1);
        JsonNode p0 = trend.get("points").get(0);
        assertThat(p0.get("stepP95Ms").asLong()).isEqualTo(writeStep.get("p95").asLong());
        assertThat(p0.get("stepErrorRate").decimalValue()).isEqualByComparingTo(writeStep.get("errorRate").decimalValue());
        assertThat(p0.get("stepRequests").asInt()).isEqualTo(writeStep.get("total").asInt());
        assertThat(p0.get("stepErrorRate").asDouble()).isPositive();
        JsonNode noStep = json(get("/api/dashboard/scenario-trend").param("scenarioId", s1).param("from", start.toString()), null);
        assertThat(noStep.get("points").get(0).get("stepP95Ms").isNull()).isTrue();
    }

    @Test
    void compare_validations() throws Exception {
        assertThat(status(get("/api/executions/compare").param("ids", UUID.randomUUID().toString()))).isEqualTo(400);
        String five = String.join(",", List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), UUID.randomUUID().toString()));
        assertThat(status(get("/api/executions/compare").param("ids", five))).isEqualTo(400);
        assertThat(status(get("/api/executions/compare").param("ids", UUID.randomUUID() + "," + UUID.randomUUID()))).isEqualTo(404);

        String appId = json(post("/api/applications"), Map.of("name", "compare-v-" + UUID.randomUUID(), "url", targetUrl)).get("id").asText();
        String sc = scenario(appId, "Validation-" + UUID.randomUUID().toString().substring(0, 6));
        Execution running = executionRepository.save(Execution.builder().scenario(scenarioRepository.findById(UUID.fromString(sc)).orElseThrow())
                .startedAt(Instant.now()).status(ExecutionStatus.RUNNING).totalSteps(2).successfulSteps(0).failedSteps(0).build());
        Execution done = executionRepository.save(Execution.builder().scenario(scenarioRepository.findById(UUID.fromString(sc)).orElseThrow())
                .startedAt(Instant.now().minusSeconds(60)).finishedAt(Instant.now()).status(ExecutionStatus.SUCCESS).totalSteps(2)
                .successfulSteps(0).failedSteps(0).duration(1000L).build());
        JsonNode err = json(get("/api/executions/compare").param("ids", running.getId() + "," + done.getId()), null);
        assertThat(err.get("message").asText()).contains("n'est pas terminée");
        executionRepository.delete(running);
    }
}
