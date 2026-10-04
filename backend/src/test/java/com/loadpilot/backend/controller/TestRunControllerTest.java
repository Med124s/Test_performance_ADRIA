package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.MetricRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** POST /api/scenarios/{id}/test-run et /api/steps/{id}/test-run de bout en bout (H2 + vrai serveur HTTP local). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TestRunControllerTest {

    private static final String TOKEN = "SESSION-TOKEN-777";
    private static HttpServer server;
    private static String targetUrl;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ExecutionRepository executionRepository;
    @Autowired
    private MetricRepository metricRepository;
    @MockBean
    private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_SUPER_ADMIN = jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
    private static final RequestPostProcessor AS_ENGINEER = jwt().authorities(new SimpleGrantedAuthority("ROLE_PERFORMANCE_ENGINEER"));
    private static final RequestPostProcessor AS_VIEWER = jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"));

    @BeforeAll
    static void startTarget() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", TestRunControllerTest::handle);
        server.start();
        targetUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopTarget() {
        server.stop(0);
    }

    private static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        ex.getRequestBody().readAllBytes();
        boolean authorized = ("Bearer " + TOKEN).equals(ex.getRequestHeaders().getFirst("Authorization"));
        String body;
        int status;
        if (path.equals("/api/auth/login")) {
            status = 200;
            body = "{\"token\":\"" + TOKEN + "\"}";
        } else if (!authorized) {
            status = 401;
            body = "{\"error\":\"unauthorized\"}";
        } else if (path.equals("/api/transfers/national")) {
            status = 201;
            body = "{\"transferId\":\"TRF900\",\"status\":\"completed\"}";
        } else if (path.equals("/api/transfers/TRF900")) {
            status = 200;
            body = "{\"success\":true}";
        } else {
            status = 404;
            body = "{}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private JsonNode postJson(String path, Object body) throws Exception {
        String response = mockMvc.perform(post(path).with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(response);
    }

    /** Application LOGIN_TOKEN + scenario virement (capture) puis PATCH /api/transfers/${transferId}. */
    private String[] createScenario(String name) throws Exception {
        JsonNode app = postJson("/api/applications", Map.of("name", name, "url", targetUrl,
                "auth", Map.of("type", "LOGIN_TOKEN", "loginPath", "/api/auth/login",
                        "loginBody", "{\"username\":\"agent001\",\"password\":\"pw-12345\"}", "tokenJsonPath", "$.token")));
        JsonNode scenario = postJson("/api/scenarios", Map.of("applicationId", app.get("id").asText(), "name", name));
        String scenarioId = scenario.get("id").asText();
        JsonNode transfer = postJson("/api/steps", Map.of("scenarioId", scenarioId, "name", "Virement national",
                "method", "POST", "url", "/api/transfers/national", "order", 1, "expectedStatus", 201,
                "headers", "{\"Content-Type\":\"application/json\"}", "body", "{\"amount\":250}",
                "captureVariableName", "transferId", "captureJsonPath", "$.transferId"));
        JsonNode patch = postJson("/api/steps", Map.of("scenarioId", scenarioId, "name", "Modifier",
                "method", "PATCH", "url", "/api/transfers/${transferId}", "order", 2, "expectedStatus", 200,
                "body", "{\"amount\":300}"));
        return new String[] {scenarioId, transfer.get("id").asText(), patch.get("id").asText()};
    }

    @Test
    void scenarioTestRun_returnsDetailedSteps_withoutCreatingExecutionOrMetrics() throws Exception {
        String[] ids = createScenario("test-run-scenario");
        long executionsBefore = executionRepository.count();
        long metricsBefore = metricRepository.count();

        String response = mockMvc.perform(post("/api/scenarios/" + ids[0] + "/test-run").with(AS_ENGINEER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.passedSteps").value(2))
                .andExpect(jsonPath("$.auth.success").value(true))
                .andExpect(jsonPath("$.steps[0].checks.capture.value").value("TRF900"))
                .andExpect(jsonPath("$.steps[1].request.url").value(targetUrl + "/api/transfers/TRF900"))
                .andExpect(jsonPath("$.steps[1].request.headers.Authorization").value("••••••"))
                .andExpect(jsonPath("$.steps[1].response.statusText").value("OK"))
                .andExpect(jsonPath("$.variables.transferId").value("TRF900"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(response).doesNotContain(TOKEN).doesNotContain("pw-12345");
        assertThat(executionRepository.count()).isEqualTo(executionsBefore);
        assertThat(metricRepository.count()).isEqualTo(metricsBefore);
    }

    @Test
    void stepTestRun_reusesProvidedVariables() throws Exception {
        String[] ids = createScenario("test-run-step");

        mockMvc.perform(post("/api/steps/" + ids[2] + "/test-run").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"variables\":{\"transferId\":\"TRF900\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSteps").value(1))
                .andExpect(jsonPath("$.steps[0].status").value("PASSED"))
                .andExpect(jsonPath("$.steps[0].request.url").value(targetUrl + "/api/transfers/TRF900"));

        // Sans la variable : echec clair, aucune requete envoyee.
        mockMvc.perform(post("/api/steps/" + ids[2] + "/test-run").with(AS_SUPER_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].errorKind").value("UNRESOLVED_VARIABLE"));
    }

    @Test
    void streamVariant_emitsProgressEventsThenTheFinalResult() throws Exception {
        String[] ids = createScenario("test-run-stream");

        MvcResult started = mockMvc.perform(post("/api/scenarios/" + ids[0] + "/test-run?stream=true").with(AS_ENGINEER))
                .andExpect(request().asyncStarted())
                .andReturn();
        String body = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        List<String> types = Arrays.stream(body.split("\n")).filter(l -> !l.isBlank())
                .map(l -> {
                    try {
                        return objectMapper.readTree(l).get("type").asText();
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }).toList();
        assertThat(types).containsExactly("started", "auth", "step-started", "step", "step-started", "step", "done");
        assertThat(body).contains("\"passedSteps\":2").doesNotContain(TOKEN);
    }

    @Test
    void viewer_isForbidden_andUnknownScenario_is404() throws Exception {
        String[] ids = createScenario("test-run-security");
        mockMvc.perform(post("/api/scenarios/" + ids[0] + "/test-run").with(AS_VIEWER)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/steps/" + ids[1] + "/test-run").with(AS_VIEWER)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/scenarios/00000000-0000-0000-0000-000000000000/test-run").with(AS_SUPER_ADMIN))
                .andExpect(status().isNotFound());
    }
}
