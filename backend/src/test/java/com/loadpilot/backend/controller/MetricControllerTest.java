package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.dto.request.ApplicationRequest;
import com.loadpilot.backend.dto.request.ExecutionRequest;
import com.loadpilot.backend.dto.request.ScenarioRequest;
import com.loadpilot.backend.dto.request.StepRequest;
import com.loadpilot.backend.enums.HttpMethod;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Verifie /api/metrics de bout en bout : la generation automatique reelle
 * des Metric apres une Execution (voir ExecutionServiceImpl.execute /
 * MetricGenerationService), les filtres combinables, et la securite en
 * lecture seule.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MetricControllerTest {

    private static HttpServer targetServer;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_SUPER_ADMIN =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
    private static final RequestPostProcessor AS_ENGINEER =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_PERFORMANCE_ENGINEER"));
    private static final RequestPostProcessor AS_VIEWER =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"));

    @BeforeAll
    static void startServers() throws IOException {
        targetServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        targetServer.createContext("/ok", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        targetServer.createContext("/fail", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        targetServer.setExecutor(daemonExecutor());
        targetServer.start();
    }

    @AfterAll
    static void stopServers() {
        targetServer.stop(0);
    }

    private static java.util.concurrent.Executor daemonExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
    }

    private String targetBaseUrl() {
        return "http://localhost:" + targetServer.getAddress().getPort();
    }

    // ------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------

    private String createApplicationAndGetId(String name) throws Exception {
        String body = objectMapper.writeValueAsString(
                new ApplicationRequest(name, "Description", targetBaseUrl()));
        String response = mockMvc.perform(post("/api/applications").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private String createScenarioAndGetId(String applicationId, String name) throws Exception {
        String body = objectMapper.writeValueAsString(
                new ScenarioRequest(UUID.fromString(applicationId), name, "Description"));
        String response = mockMvc.perform(post("/api/scenarios").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private String createStepAndGetId(String scenarioId, String name, int order, String path, Integer expectedStatus) throws Exception {
        String body = objectMapper.writeValueAsString(
                new StepRequest(UUID.fromString(scenarioId), name, HttpMethod.GET, path, null, null, order, expectedStatus,
                        null, null, null, null, null, null));
        String response = mockMvc.perform(post("/api/steps").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    /** P0-A : POST /api/executions est desormais ASYNCHRONE (202, statut
     * QUEUED) - on attend reellement l'etat terminal avant de rendre la
     * main, pour que les tests qui consultent ensuite /api/metrics voient
     * des donnees reellement generees (voir waitForTerminalStatus). */
    private String executeScenarioAndGetId(String scenarioId) throws Exception {
        String body = objectMapper.writeValueAsString(new ExecutionRequest(UUID.fromString(scenarioId)));
        String response = mockMvc.perform(post("/api/executions").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String executionId = objectMapper.readTree(response).get("id").asText();
        waitForTerminalStatus(executionId);
        return executionId;
    }

    private void waitForTerminalStatus(String executionId) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            String response = mockMvc.perform(get("/api/executions/" + executionId).with(AS_VIEWER))
                    .andReturn().getResponse().getContentAsString();
            String status = objectMapper.readTree(response).get("status").asText();
            if (!status.equals("QUEUED") && !status.equals("RUNNING")) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Execution " + executionId + " n'a pas atteint un statut terminal a temps.");
    }

    private JsonNode getMetricsByExecution(String executionId) throws Exception {
        String response = mockMvc.perform(get("/api/metrics").param("executionId", executionId).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    /** Application + Scenario + 1 Step reussi (cible reelle /ok), execute. Renvoie l'executionId. */
    private String runSuccessfulOneStepScenario(String prefix) throws Exception {
        String appId = createApplicationAndGetId(prefix + "-app");
        String scenarioId = createScenarioAndGetId(appId, prefix + "-scenario");
        createStepAndGetId(scenarioId, prefix + "-step", 1, "/ok", 200);
        return executeScenarioAndGetId(scenarioId);
    }

    // ------------------------------------------------------------
    // Securite
    // ------------------------------------------------------------

    @Test
    void list_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/metrics")).andExpect(status().isUnauthorized());
    }

    @Test
    void getById_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/metrics/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void viewerRole_canListMetrics() throws Exception {
        mockMvc.perform(get("/api/metrics").with(AS_VIEWER)).andExpect(status().isOk());
    }

    @Test
    void engineerRole_canListMetrics() throws Exception {
        mockMvc.perform(get("/api/metrics").with(AS_ENGINEER)).andExpect(status().isOk());
    }

    @Test
    void superAdminRole_canListMetrics() throws Exception {
        mockMvc.perform(get("/api/metrics").with(AS_SUPER_ADMIN)).andExpect(status().isOk());
    }

    // ------------------------------------------------------------
    // Generation automatique / valeurs reelles
    // ------------------------------------------------------------

    @Test
    void successfulExecution_generatesOneMetricWithRealStatusCodeAndResponseTime() throws Exception {
        String executionId = runSuccessfulOneStepScenario("real-values");

        JsonNode metrics = getMetricsByExecution(executionId);

        assertThat(metrics.size()).isEqualTo(1);
        JsonNode metric = metrics.get(0);
        assertThat(metric.get("executionId").asText()).isEqualTo(executionId);
        assertThat(metric.get("statusCode").asInt()).isEqualTo(200);
        assertThat(metric.get("responseTime").asInt()).isGreaterThanOrEqualTo(0);
        assertThat(metric.get("errorRate").asDouble()).isEqualTo(0.0);
        assertThat(metric.get("throughput").isNull()).isFalse();
        assertThat(metric.get("throughput").asDouble()).isGreaterThan(0.0);
    }

    @Test
    void failingStep_errorRateIsHundredPercent() throws Exception {
        String appId = createApplicationAndGetId("fail-rate-app");
        String scenarioId = createScenarioAndGetId(appId, "fail-rate-scenario");
        createStepAndGetId(scenarioId, "fail-rate-step", 1, "/fail", 200);
        String executionId = executeScenarioAndGetId(scenarioId);

        JsonNode metrics = getMetricsByExecution(executionId);

        assertThat(metrics.size()).isEqualTo(1);
        assertThat(metrics.get(0).get("statusCode").asInt()).isEqualTo(500);
        assertThat(metrics.get(0).get("errorRate").asDouble()).isEqualTo(100.0);
    }

    @Test
    void multiStepScenario_stoppedEarly_errorRateReflectsExecutedStepsNotTotalSteps() throws Exception {
        String appId = createApplicationAndGetId("partial-fail-app");
        String scenarioId = createScenarioAndGetId(appId, "partial-fail-scenario");
        createStepAndGetId(scenarioId, "step-1-fails", 1, "/fail", 200);
        createStepAndGetId(scenarioId, "step-2-never-runs", 2, "/ok", 200);
        String executionId = executeScenarioAndGetId(scenarioId);

        JsonNode metrics = getMetricsByExecution(executionId);

        // Un seul Metric : seul le 1er step a reellement ete execute (stop-on-failure).
        assertThat(metrics.size()).isEqualTo(1);
        // errorRate = 1 echec / 1 step EXECUTE * 100 = 100%, PAS 1/2 (totalSteps) = 50%.
        assertThat(metrics.get(0).get("errorRate").asDouble()).isEqualTo(100.0);
    }

    // ------------------------------------------------------------
    // Filtres
    // ------------------------------------------------------------

    @Test
    void filter_byApplicationId_returnsOnlyThatApplicationMetrics() throws Exception {
        String appAId = createApplicationAndGetId("filter-app-a");
        String scenarioAId = createScenarioAndGetId(appAId, "filter-scenario-a");
        createStepAndGetId(scenarioAId, "step-a", 1, "/ok", 200);
        String execA = executeScenarioAndGetId(scenarioAId);
        runSuccessfulOneStepScenario("filter-app-b");

        mockMvc.perform(get("/api/metrics").param("applicationId", appAId).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].executionId").value(execA));
    }

    @Test
    void filter_byScenarioId_returnsOnlyThatScenarioMetrics() throws Exception {
        String appId = createApplicationAndGetId("filter-scenario-app");
        String scenarioId = createScenarioAndGetId(appId, "filter-scenario-target");
        createStepAndGetId(scenarioId, "step", 1, "/ok", 200);
        String executionId = executeScenarioAndGetId(scenarioId);

        mockMvc.perform(get("/api/metrics").param("scenarioId", scenarioId).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].executionId").value(executionId));
    }

    @Test
    void filter_byStepId_returnsOnlyThatStepMetrics() throws Exception {
        String appId = createApplicationAndGetId("filter-step-app");
        String scenarioId = createScenarioAndGetId(appId, "filter-step-scenario");
        String stepId = createStepAndGetId(scenarioId, "filter-step", 1, "/ok", 200);
        executeScenarioAndGetId(scenarioId);

        mockMvc.perform(get("/api/metrics").param("stepId", stepId).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].stepId").value(stepId));
    }

    @Test
    void filter_byExecutionId_returnsOnlyThatExecutionMetrics() throws Exception {
        String executionId = runSuccessfulOneStepScenario("filter-execution");

        mockMvc.perform(get("/api/metrics").param("executionId", executionId).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].executionId").value(executionId));
    }

    @Test
    void filter_combinedApplicationAndExecution_appliesAndLogic() throws Exception {
        String appId = createApplicationAndGetId("combined-filter-app");
        String scenarioId = createScenarioAndGetId(appId, "combined-filter-scenario");
        createStepAndGetId(scenarioId, "combined-step", 1, "/ok", 200);
        String executionId = executeScenarioAndGetId(scenarioId);
        String otherExecutionId = runSuccessfulOneStepScenario("combined-filter-other");

        mockMvc.perform(get("/api/metrics")
                        .param("applicationId", appId)
                        .param("executionId", executionId)
                        .with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].executionId").value(executionId));

        mockMvc.perform(get("/api/metrics")
                        .param("applicationId", appId)
                        .param("executionId", otherExecutionId)
                        .with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void filter_nonexistentApplicationId_returns404() throws Exception {
        mockMvc.perform(get("/api/metrics").param("applicationId", UUID.randomUUID().toString()).with(AS_VIEWER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void filter_nonexistentExecutionId_returns404() throws Exception {
        mockMvc.perform(get("/api/metrics").param("executionId", UUID.randomUUID().toString()).with(AS_VIEWER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void getById_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/api/metrics/" + UUID.randomUUID()).with(AS_VIEWER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void getById_invalidUuidFormat_returns400() throws Exception {
        mockMvc.perform(get("/api/metrics/not-a-uuid").with(AS_VIEWER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void getById_returnsSingleMetric() throws Exception {
        String executionId = runSuccessfulOneStepScenario("get-by-id");
        String metricId = getMetricsByExecution(executionId).get(0).get("id").asText();

        mockMvc.perform(get("/api/metrics/" + metricId).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(metricId))
                .andExpect(jsonPath("$.executionId").value(executionId));
    }
}
