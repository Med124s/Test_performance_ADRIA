package com.loadpilot.backend.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * Verifie /api/dashboard de bout en bout : donnees REELLES issues d'une
 * vraie Execution HTTP (serveur JDK embarque, jamais banking-test-api ni
 * local-monitoring-server), securite en lecture seule, et le cas
 * Application inexistante (404, jamais un dashboard vide).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardControllerTest {

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
    static void startServer() throws IOException {
        targetServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        targetServer.createContext("/ok", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        targetServer.createContext("/fail", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        targetServer.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }));
        targetServer.start();
    }

    @AfterAll
    static void stopServer() {
        targetServer.stop(0);
    }

    private String targetBaseUrl() {
        return "http://localhost:" + targetServer.getAddress().getPort();
    }

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

    private void createStep(String scenarioId, String name, int order, String path, Integer expectedStatus) throws Exception {
        String body = objectMapper.writeValueAsString(
                new StepRequest(UUID.fromString(scenarioId), name, HttpMethod.GET, path, null, null, order, expectedStatus,
                        null, null, null, null, null, null));
        mockMvc.perform(post("/api/steps").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    /**
     * Lance une execution et attend qu'elle atteigne un statut terminal avant de rendre
     * la main : /api/executions est desormais asynchrone (202/QUEUED), donc le dashboard
     * ne doit etre interroge qu'une fois l'execution reellement terminee.
     */
    private void executeScenario(String scenarioId) throws Exception {
        String body = objectMapper.writeValueAsString(new ExecutionRequest(UUID.fromString(scenarioId)));
        String response = mockMvc.perform(post("/api/executions").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String executionId = objectMapper.readTree(response).get("id").asText();

        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            String statusResponse = mockMvc.perform(get("/api/executions/" + executionId).with(AS_VIEWER))
                    .andReturn().getResponse().getContentAsString();
            String status = objectMapper.readTree(statusResponse).get("status").asText();
            if (!status.equals("QUEUED") && !status.equals("RUNNING")) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Execution " + executionId + " n'a pas atteint un statut terminal a temps.");
    }

    // ------------------------------------------------------------
    // Securite
    // ------------------------------------------------------------

    @Test
    void globalDashboard_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized());
    }

    @Test
    void applicationDashboard_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/dashboard/applications/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void viewerRole_canGetGlobalDashboard() throws Exception {
        mockMvc.perform(get("/api/dashboard").with(AS_VIEWER)).andExpect(status().isOk());
    }

    @Test
    void engineerRole_canGetGlobalDashboard() throws Exception {
        mockMvc.perform(get("/api/dashboard").with(AS_ENGINEER)).andExpect(status().isOk());
    }

    @Test
    void superAdminRole_canGetGlobalDashboard() throws Exception {
        mockMvc.perform(get("/api/dashboard").with(AS_SUPER_ADMIN)).andExpect(status().isOk());
    }

    @Test
    void viewerRole_canGetApplicationDashboard() throws Exception {
        String appId = createApplicationAndGetId("viewer-dashboard-app");
        mockMvc.perform(get("/api/dashboard/applications/" + appId).with(AS_VIEWER)).andExpect(status().isOk());
    }

    // ------------------------------------------------------------
    // Dashboard global - sanite (base partagee entre tests, pas de compte exact)
    // ------------------------------------------------------------

    @Test
    void globalDashboard_reflectsAtLeastTheDataJustCreated() throws Exception {
        createApplicationAndGetId("global-sanity-app");

        mockMvc.perform(get("/api/dashboard").with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applications.totalApplications").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    // ------------------------------------------------------------
    // Dashboard par Application - valeurs reelles
    // ------------------------------------------------------------

    @Test
    void applicationDashboard_successfulExecution_reflectsRealCounts() throws Exception {
        String appId = createApplicationAndGetId("success-dashboard-app");
        String scenarioId = createScenarioAndGetId(appId, "success-dashboard-scenario");
        createStep(scenarioId, "step", 1, "/ok", 200);
        executeScenario(scenarioId);

        mockMvc.perform(get("/api/dashboard/applications/" + appId).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application.id").value(appId))
                .andExpect(jsonPath("$.scenarios.totalScenarios").value(1))
                .andExpect(jsonPath("$.scenarios.activeScenarios").value(1))
                .andExpect(jsonPath("$.executions.totalExecutions").value(1))
                .andExpect(jsonPath("$.executions.successfulExecutions").value(1))
                .andExpect(jsonPath("$.executions.failedExecutions").value(0))
                .andExpect(jsonPath("$.executions.totalStepsExecuted").value(1))
                .andExpect(jsonPath("$.executions.successfulSteps").value(1))
                .andExpect(jsonPath("$.executions.failedSteps").value(0))
                .andExpect(jsonPath("$.executions.successRate").value(100.0))
                .andExpect(jsonPath("$.executions.failureRate").value(0.0));
    }

    @Test
    void applicationDashboard_failingExecution_reflectsRealFailureCounts() throws Exception {
        String appId = createApplicationAndGetId("fail-dashboard-app");
        String scenarioId = createScenarioAndGetId(appId, "fail-dashboard-scenario");
        createStep(scenarioId, "step", 1, "/fail", 200);
        executeScenario(scenarioId);

        mockMvc.perform(get("/api/dashboard/applications/" + appId).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executions.failedExecutions").value(1))
                .andExpect(jsonPath("$.executions.failedSteps").value(1))
                .andExpect(jsonPath("$.executions.successRate").value(0.0))
                .andExpect(jsonPath("$.executions.failureRate").value(100.0));
    }

    @Test
    void applicationDashboard_afterExecution_performanceComesFromRealMetric() throws Exception {
        String appId = createApplicationAndGetId("performance-dashboard-app");
        String scenarioId = createScenarioAndGetId(appId, "performance-dashboard-scenario");
        createStep(scenarioId, "step", 1, "/ok", 200);
        executeScenario(scenarioId);

        // Metric generee automatiquement (Phase 10) apres l'Execution reelle
        // ci-dessus : averageResponseTime doit exister (jamais invente),
        // averageErrorRate doit refleter 0% (le seul step a reussi).
        mockMvc.perform(get("/api/dashboard/applications/" + appId).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.performance.averageResponseTime").exists())
                .andExpect(jsonPath("$.performance.averageResponseTime").isNotEmpty())
                .andExpect(jsonPath("$.performance.averageErrorRate").value(0.0));
    }

    @Test
    void applicationDashboard_withoutAnyScenarioOrExecution_returnsZeroesAndNullRates() throws Exception {
        String appId = createApplicationAndGetId("empty-dashboard-app");

        mockMvc.perform(get("/api/dashboard/applications/" + appId).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenarios.totalScenarios").value(0))
                .andExpect(jsonPath("$.executions.totalExecutions").value(0))
                .andExpect(jsonPath("$.executions.totalStepsExecuted").value(0))
                .andExpect(jsonPath("$.executions.successRate").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.executions.failureRate").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.performance.averageResponseTime").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void applicationDashboard_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/api/dashboard/applications/" + UUID.randomUUID()).with(AS_VIEWER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void applicationDashboard_invalidUuidFormat_returns400() throws Exception {
        mockMvc.perform(get("/api/dashboard/applications/not-a-uuid").with(AS_VIEWER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }
}
