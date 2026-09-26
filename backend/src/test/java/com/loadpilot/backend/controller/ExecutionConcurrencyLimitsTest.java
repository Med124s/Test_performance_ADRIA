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
 * P0-B (prompt section 7) - preuve REELLE des limites de capacite
 * configurables : contexte Spring DEDIE avec des limites volontairement
 * basses (proprietes @SpringBootTest, jamais les valeurs de production) pour
 * declencher les refus de maniere deterministe et rapide, sans devoir
 * lancer des dizaines de VUs reels pour saturer les valeurs par defaut.
 *
 * Chaque refus doit etre : deterministe, explicite (message clair), HTTP
 * correct (429), journalise (voir AuditTrailIntegrationTest pour la
 * couverture d'audit generale) - et surtout, aucune Execution ne doit
 * jamais etre creee en base pour une tentative refusee (jamais un blocage
 * silencieux apres acceptation).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "app.execution.max-concurrent-executions=2",
        "app.execution.max-global-virtual-users=6",
        "app.execution.max-virtual-users-per-execution=5"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExecutionConcurrencyLimitsTest {

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
    static void startTargetServer() throws IOException {
        targetServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        targetServer.createContext("/slow", exchange -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
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
    static void stopTargetServer() {
        targetServer.stop(0);
    }

    private String targetBaseUrl() {
        return "http://localhost:" + targetServer.getAddress().getPort();
    }

    private String createApplicationAndGetId(String name) throws Exception {
        String body = objectMapper.writeValueAsString(new ApplicationRequest(name, "d", targetBaseUrl()));
        String response = mockMvc.perform(post("/api/applications").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private String createScenarioAndGetId(String applicationId, String name, int virtualUsers) throws Exception {
        String body = objectMapper.writeValueAsString(
                new ScenarioRequest(UUID.fromString(applicationId), name, "d", virtualUsers, 0, null, null, 0, null, null));
        String response = mockMvc.perform(post("/api/scenarios").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private void createSlowStep(String scenarioId) throws Exception {
        String body = objectMapper.writeValueAsString(
                new StepRequest(UUID.fromString(scenarioId), "slow-step", HttpMethod.GET, "/slow", null, null, 1, 200,
                        null, null, null, null, null, null));
        mockMvc.perform(post("/api/steps").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private String submitExecution(String scenarioId) throws Exception {
        String body = objectMapper.writeValueAsString(new ExecutionRequest(UUID.fromString(scenarioId)));
        String response = mockMvc.perform(post("/api/executions").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private String currentStatus(String executionId) throws Exception {
        String response = mockMvc.perform(get("/api/executions/" + executionId).with(AS_VIEWER))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("status").asText();
    }

    private void waitForTerminalStatus(String executionId) throws Exception {
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            String status = currentStatus(executionId);
            if (!status.equals("QUEUED") && !status.equals("RUNNING")) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Execution " + executionId + " n'a pas atteint un statut terminal a temps.");
    }

    // ------------------------------------------------------------
    // Limite : nombre maximum d'executions simultanees (2 ici)
    // ------------------------------------------------------------

    @Test
    void thirdConcurrentExecution_isRejectedWhileTwoAreActive_thenSucceedsOnceCapacityFrees() throws Exception {
        String appId = createApplicationAndGetId("limit-concurrent-app-" + UUID.randomUUID());
        String scenarioId = createScenarioAndGetId(appId, "limit-concurrent-scenario", 1);
        createSlowStep(scenarioId);

        String exec1 = submitExecution(scenarioId);
        String exec2 = submitExecution(scenarioId);

        // 2 executions actives (RUNNING/QUEUED) = la limite exacte - une 3e
        // doit etre refusee de maniere deterministe, AVANT toute creation.
        String body = objectMapper.writeValueAsString(new ExecutionRequest(UUID.fromString(scenarioId)));
        mockMvc.perform(post("/api/executions").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429));

        waitForTerminalStatus(exec1);
        waitForTerminalStatus(exec2);

        // Capacite reellement liberee (jamais une fuite) : une nouvelle
        // execution redevient possible une fois les deux premieres terminees.
        String exec3 = submitExecution(scenarioId);
        waitForTerminalStatus(exec3);
    }

    // ------------------------------------------------------------
    // Limite : nombre maximum d'utilisateurs virtuels GLOBAUX (6 ici)
    // ------------------------------------------------------------

    @Test
    void executionExceedingRemainingGlobalVirtualUserBudget_isRejected() throws Exception {
        String appId = createApplicationAndGetId("limit-global-vus-app-" + UUID.randomUUID());
        String bigScenarioId = createScenarioAndGetId(appId, "limit-global-vus-big", 5);
        createSlowStep(bigScenarioId);
        String smallScenarioId = createScenarioAndGetId(appId, "limit-global-vus-small", 2);
        createSlowStep(smallScenarioId);

        // Reserve 5 des 6 VUs globaux disponibles (max-concurrent-executions=2
        // n'est pas encore atteint : une seule execution active).
        String bigExec = submitExecution(bigScenarioId);

        // 5 (deja reserves) + 2 (demandes) = 7 > 6 (limite globale) - refuse
        // meme si le nombre d'executions simultanees (1 < 2) le permettrait.
        String body = objectMapper.writeValueAsString(new ExecutionRequest(UUID.fromString(smallScenarioId)));
        mockMvc.perform(post("/api/executions").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429));

        waitForTerminalStatus(bigExec);
    }

    // ------------------------------------------------------------
    // Limite : nombre maximum d'utilisateurs virtuels PAR EXECUTION (5 ici)
    // ------------------------------------------------------------

    @Test
    void scenarioRequestingMoreVirtualUsersThanPerExecutionLimit_isRejectedAtConfigurationTime() throws Exception {
        String appId = createApplicationAndGetId("limit-per-exec-app-" + UUID.randomUUID());
        String body = objectMapper.writeValueAsString(
                new ScenarioRequest(UUID.fromString(appId), "limit-per-exec-scenario", "d", 6, 0, null, null, 0, null, null));

        mockMvc.perform(post("/api/scenarios").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }
}
