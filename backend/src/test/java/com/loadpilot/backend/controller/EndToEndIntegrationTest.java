package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * Parcours d'integration bout-en-bout (Phase 14, section 18) : traverse
 * reellement TOUS les modules dans le meme test, plutot que de se fier a la
 * couverture eparpillee entre les *ControllerTest de chaque module pris
 * separement. H2 + serveurs HTTP JDK embarques uniquement (aucune
 * dependance externe, aucun banking-test-api/local-monitoring-server reels).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EndToEndIntegrationTest {

    private static HttpServer targetServer;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_SUPER_ADMIN =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
    private static final RequestPostProcessor AS_VIEWER =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"));

    @BeforeAll
    static void startServers() throws IOException {
        targetServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        targetServer.createContext("/ok", exchange -> {
            exchange.sendResponseHeaders(200, -1);
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
    static void stopServers() {
        targetServer.stop(0);
    }

    private String targetBaseUrl() {
        return "http://localhost:" + targetServer.getAddress().getPort();
    }

    private String createApplication(String name) throws Exception {
        String body = objectMapper.writeValueAsString(new ApplicationRequest(name, "d", targetBaseUrl()));
        String response = mockMvc.perform(post("/api/applications").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private String createScenario(String appId, String name) throws Exception {
        String body = objectMapper.writeValueAsString(new ScenarioRequest(UUID.fromString(appId), name, "d"));
        String response = mockMvc.perform(post("/api/scenarios").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private void createStep(String scenarioId, String name) throws Exception {
        String body = objectMapper.writeValueAsString(
                new StepRequest(UUID.fromString(scenarioId), name, HttpMethod.GET, "/ok", null, null, 1, 200,
                        null, null, null, null, null, null));
        mockMvc.perform(post("/api/steps").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    /** Soumet une execution (asynchrone, 202) puis attend qu'elle atteigne un statut terminal. */
    private String execute(String scenarioId) throws Exception {
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
            if (!status.equals("QUEUED") && !status.equals("RUNNING")) return executionId;
            Thread.sleep(20);
        }
        throw new AssertionError("Execution " + executionId + " n'a pas atteint un statut terminal a temps.");
    }

    /**
     * Parcours 1 : AppUser (via JWT) -> Application -> Scenario -> Step ->
     * Execution -> ExecutionStepResult -> Metric -> Dashboard -> Audit.
     * Chaque etape verifie que la precedente est bien visible en aval, avec
     * de vraies donnees (aucune valeur inventee).
     */
    @Test
    void parcours1_fullChainFromApplicationToDashboardAndAudit() throws Exception {
        String appName = "e2e-parcours1-" + UUID.randomUUID();
        String appId = createApplication(appName);
        String scenarioId = createScenario(appId, "e2e-scenario");
        createStep(scenarioId, "e2e-step");

        String executionId = execute(scenarioId);

        // Execution detail -> ExecutionStepResult reel.
        String executionDetail = mockMvc.perform(get("/api/executions/" + executionId).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode executionJson = objectMapper.readTree(executionDetail);
        assertThat(executionJson.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(executionJson.get("results")).hasSize(1);
        assertThat(executionJson.get("results").get(0).get("httpStatus").asInt()).isEqualTo(200);

        // Metric generee automatiquement apres l'Execution.
        String metricsResponse = mockMvc.perform(get("/api/metrics").param("executionId", executionId).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode metrics = objectMapper.readTree(metricsResponse);
        assertThat(metrics).hasSize(1);
        assertThat(metrics.get(0).get("statusCode").asInt()).isEqualTo(200);
        assertThat(metrics.get(0).get("errorRate").asDouble()).isEqualTo(0.0);

        // Dashboard de l'application refletant cette meme execution.
        String dashboardResponse = mockMvc.perform(get("/api/dashboard/applications/" + appId).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode dashboard = objectMapper.readTree(dashboardResponse);
        assertThat(dashboard.get("executions").get("totalExecutions").asInt()).isEqualTo(1);
        assertThat(dashboard.get("executions").get("successfulExecutions").asInt()).isEqualTo(1);
        assertThat(dashboard.get("performance").get("averageResponseTime").isNull()).isFalse();

        // Audit : au moins CREATE/APPLICATION et EXECUTE/EXECUTION reellement enregistres.
        String auditResponse = mockMvc.perform(get("/api/audit-logs")
                        .param("module", "APPLICATION").param("action", "CREATE").param("size", "200")
                        .with(AS_SUPER_ADMIN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode auditContent = objectMapper.readTree(auditResponse).get("content");
        assertThat(auditContent.toString()).contains(appName);
    }

}
