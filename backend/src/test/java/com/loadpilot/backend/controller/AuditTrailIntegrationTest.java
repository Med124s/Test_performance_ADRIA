package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.dto.request.ApplicationRequest;
import com.loadpilot.backend.dto.request.ExecutionRequest;
import com.loadpilot.backend.dto.request.ScenarioRequest;
import com.loadpilot.backend.dto.request.StepRequest;
import com.loadpilot.backend.entity.AuditLog;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.enums.HttpMethod;
import com.loadpilot.backend.repository.AuditLogRepository;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
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
 * Preuve que les actions metier reelles (Phase 6-9) generent effectivement
 * des AuditLog (Phase 12, section 14) - pas seulement une table consultable
 * inerte. Chaque assertion interroge directement AuditLogRepository (jamais
 * l'API /api/audit-logs, hors-sujet ici) pour verifier qu'une entree
 * correspondante existe reellement.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditTrailIntegrationTest {

    private static HttpServer targetServer;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AuditLogRepository auditLogRepository;

    @MockBean
    private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_SUPER_ADMIN =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));

    @BeforeAll
    static void startTargetServer() throws IOException {
        targetServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        targetServer.createContext("/ok", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        targetServer.createContext("/fail", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
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

    /** Soumet une execution (asynchrone, 202) et retourne son id sans attendre qu'elle termine. */
    private String submitExecutionWithoutWaiting(String scenarioId) throws Exception {
        String body = objectMapper.writeValueAsString(new ExecutionRequest(UUID.fromString(scenarioId)));
        String response = mockMvc.perform(post("/api/executions").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private String currentStatus(String executionId) throws Exception {
        String response = mockMvc.perform(get("/api/executions/" + executionId).with(AS_SUPER_ADMIN))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("status").asText();
    }

    /**
     * L'audit EXECUTE (SUCCESS/FAILURE) n'est desormais ecrit qu'a la toute
     * fin de la tache asynchrone (voir ExecutionServiceImpl.runAsync) : les
     * tests qui verifient cet audit doivent attendre un statut terminal.
     */
    private void waitForTerminalStatus(String executionId) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            String status = currentStatus(executionId);
            if (!status.equals("QUEUED") && !status.equals("RUNNING")) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Execution " + executionId + " n'a pas atteint un statut terminal a temps.");
    }

    private void waitForStatus(String executionId, String expectedStatus) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (currentStatus(executionId).equals(expectedStatus)) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Execution " + executionId + " n'a pas atteint le statut " + expectedStatus + " a temps.");
    }

    private boolean hasAuditEntry(AuditAction action, AuditModule module, AuditResult result, String descriptionContains) {
        return auditLogRepository.findAll().stream().anyMatch(a ->
                a.getAction() == action && a.getModule() == module && a.getResult() == result
                        && (descriptionContains == null || (a.getDescription() != null && a.getDescription().contains(descriptionContains))));
    }

    /**
     * L'audit EXECUTE est ecrit APRES la finalisation du statut ET apres la
     * generation des metriques (voir ExecutionServiceImpl.runAsync) : un
     * GET montrant deja un statut terminal ne garantit donc pas que cet
     * audit precis existe deja au meme instant (course reelle entre deux
     * ecritures asynchrones distinctes, pas juste une lenteur de test).
     * Attendre l'entree d'audit elle-meme, plutot que le seul statut, est
     * donc necessaire pour verifier cet audit de maniere fiable.
     */
    private void waitForAuditEntry(AuditAction action, AuditModule module, AuditResult result, String descriptionContains) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (hasAuditEntry(action, module, result, descriptionContains)) return;
            Thread.sleep(20);
        }
        throw new AssertionError("AuditLog attendu introuvable a temps : " + action + "/" + module + "/" + result
                + " contenant '" + descriptionContains + "'.");
    }

    private String createApplicationAndGetId(String name) throws Exception {
        String body = objectMapper.writeValueAsString(new ApplicationRequest(name, "Description", targetBaseUrl()));
        String response = mockMvc.perform(post("/api/applications").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private String createScenarioAndGetId(String applicationId, String name) throws Exception {
        String body = objectMapper.writeValueAsString(new ScenarioRequest(UUID.fromString(applicationId), name, "Description"));
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

    // ------------------------------------------------------------
    // Applications
    // ------------------------------------------------------------

    @Test
    void applicationCreate_producesSuccessAuditLog() throws Exception {
        String name = "audit-trail-app-" + UUID.randomUUID();
        createApplicationAndGetId(name);

        assertThat(hasAuditEntry(AuditAction.CREATE, AuditModule.APPLICATION, AuditResult.SUCCESS, name)).isTrue();
    }

    @Test
    void applicationUpdate_producesSuccessAuditLog() throws Exception {
        String appId = createApplicationAndGetId("audit-trail-app-upd-" + UUID.randomUUID());
        String newName = "audit-trail-app-updated-" + UUID.randomUUID();
        String body = objectMapper.writeValueAsString(new ApplicationRequest(newName, "d", targetBaseUrl()));

        mockMvc.perform(put("/api/applications/" + appId).with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        assertThat(hasAuditEntry(AuditAction.UPDATE, AuditModule.APPLICATION, AuditResult.SUCCESS, newName)).isTrue();
    }

    @Test
    void applicationDelete_producesSuccessAuditLog() throws Exception {
        String name = "audit-trail-app-del-" + UUID.randomUUID();
        String appId = createApplicationAndGetId(name);

        mockMvc.perform(delete("/api/applications/" + appId).with(AS_SUPER_ADMIN)).andExpect(status().isNoContent());

        assertThat(hasAuditEntry(AuditAction.DELETE, AuditModule.APPLICATION, AuditResult.SUCCESS, name)).isTrue();
    }

    @Test
    void applicationDelete_withAttachedScenario_producesFailureAuditLog() throws Exception {
        String appId = createApplicationAndGetId("audit-trail-app-conflict-" + UUID.randomUUID());
        createScenarioAndGetId(appId, "blocking-scenario");

        mockMvc.perform(delete("/api/applications/" + appId).with(AS_SUPER_ADMIN)).andExpect(status().isConflict());

        assertThat(hasAuditEntry(AuditAction.DELETE, AuditModule.APPLICATION, AuditResult.FAILURE, appId)).isTrue();
    }

    @Test
    void applicationTest_producesSuccessAuditLog() throws Exception {
        String name = "audit-trail-app-test-" + UUID.randomUUID();
        String appId = createApplicationAndGetId(name);

        mockMvc.perform(post("/api/applications/" + appId + "/test").with(AS_SUPER_ADMIN)).andExpect(status().isOk());

        // La description reference le NOM et le statut resultant, pas l'id
        // (voir ApplicationServiceImpl.testAvailability) - la cible reelle
        // (targetServer, /ok implicite via GET racine) repond 200 -> CONNECTED.
        assertThat(hasAuditEntry(AuditAction.TEST, AuditModule.APPLICATION, AuditResult.SUCCESS, name)).isTrue();
    }

    // ------------------------------------------------------------
    // Scenarios
    // ------------------------------------------------------------

    @Test
    void scenarioCreate_producesSuccessAuditLog() throws Exception {
        String appId = createApplicationAndGetId("audit-trail-scenario-app-" + UUID.randomUUID());
        String scenarioName = "audit-trail-scenario-" + UUID.randomUUID();
        createScenarioAndGetId(appId, scenarioName);

        assertThat(hasAuditEntry(AuditAction.CREATE, AuditModule.SCENARIO, AuditResult.SUCCESS, scenarioName)).isTrue();
    }

    @Test
    void scenarioCreate_nonexistentApplication_producesFailureAuditLog() throws Exception {
        String body = objectMapper.writeValueAsString(new ScenarioRequest(UUID.randomUUID(), "orphan", "d"));

        mockMvc.perform(post("/api/scenarios").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());

        assertThat(hasAuditEntry(AuditAction.CREATE, AuditModule.SCENARIO, AuditResult.FAILURE, null)).isTrue();
    }

    @Test
    void scenarioUpdate_producesSuccessAuditLog() throws Exception {
        String appId = createApplicationAndGetId("audit-trail-scenario-upd-app-" + UUID.randomUUID());
        String scenarioId = createScenarioAndGetId(appId, "scenario-to-update");
        String newName = "scenario-updated-" + UUID.randomUUID();
        String body = objectMapper.writeValueAsString(new ScenarioRequest(UUID.fromString(appId), newName, "d"));

        mockMvc.perform(put("/api/scenarios/" + scenarioId).with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        assertThat(hasAuditEntry(AuditAction.UPDATE, AuditModule.SCENARIO, AuditResult.SUCCESS, newName)).isTrue();
    }

    @Test
    void scenarioDelete_producesSuccessAuditLog() throws Exception {
        String appId = createApplicationAndGetId("audit-trail-scenario-del-app-" + UUID.randomUUID());
        String scenarioName = "scenario-to-delete-" + UUID.randomUUID();
        String scenarioId = createScenarioAndGetId(appId, scenarioName);

        mockMvc.perform(delete("/api/scenarios/" + scenarioId).with(AS_SUPER_ADMIN)).andExpect(status().isNoContent());

        assertThat(hasAuditEntry(AuditAction.DELETE, AuditModule.SCENARIO, AuditResult.SUCCESS, scenarioName)).isTrue();
    }

    // ------------------------------------------------------------
    // Steps
    // ------------------------------------------------------------

    @Test
    void stepCreate_producesSuccessAuditLog() throws Exception {
        String appId = createApplicationAndGetId("audit-trail-step-app-" + UUID.randomUUID());
        String scenarioId = createScenarioAndGetId(appId, "step-scenario");
        String stepName = "audit-trail-step-" + UUID.randomUUID();

        createStepAndGetId(scenarioId, stepName, 1, "/ok", 200);

        assertThat(hasAuditEntry(AuditAction.CREATE, AuditModule.STEP, AuditResult.SUCCESS, stepName)).isTrue();
    }

    @Test
    void stepUpdate_producesSuccessAuditLog() throws Exception {
        String appId = createApplicationAndGetId("audit-trail-step-upd-app-" + UUID.randomUUID());
        String scenarioId = createScenarioAndGetId(appId, "step-scenario-upd");
        String stepId = createStepAndGetId(scenarioId, "step-original", 1, "/ok", 200);
        String newName = "step-updated-" + UUID.randomUUID();
        String body = objectMapper.writeValueAsString(
                new StepRequest(UUID.fromString(scenarioId), newName, HttpMethod.GET, "/ok", null, null, 1, 200,
                        null, null, null, null, null, null));

        mockMvc.perform(put("/api/steps/" + stepId).with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        assertThat(hasAuditEntry(AuditAction.UPDATE, AuditModule.STEP, AuditResult.SUCCESS, newName)).isTrue();
    }

    @Test
    void stepDelete_producesSuccessAuditLog() throws Exception {
        String appId = createApplicationAndGetId("audit-trail-step-del-app-" + UUID.randomUUID());
        String scenarioId = createScenarioAndGetId(appId, "step-scenario-del");
        String stepName = "step-to-delete-" + UUID.randomUUID();
        String stepId = createStepAndGetId(scenarioId, stepName, 1, "/ok", 200);

        mockMvc.perform(delete("/api/steps/" + stepId).with(AS_SUPER_ADMIN)).andExpect(status().isNoContent());

        assertThat(hasAuditEntry(AuditAction.DELETE, AuditModule.STEP, AuditResult.SUCCESS, stepName)).isTrue();
    }

    // ------------------------------------------------------------
    // Executions
    // ------------------------------------------------------------

    @Test
    void executionExecute_success_producesSuccessAuditLog() throws Exception {
        String appId = createApplicationAndGetId("audit-trail-exec-app-" + UUID.randomUUID());
        String scenarioId = createScenarioAndGetId(appId, "exec-scenario-ok");
        createStepAndGetId(scenarioId, "step", 1, "/ok", 200);

        String executionId = submitExecutionWithoutWaiting(scenarioId);
        waitForTerminalStatus(executionId);

        waitForAuditEntry(AuditAction.EXECUTE, AuditModule.EXECUTION, AuditResult.SUCCESS, executionId);
    }

    @Test
    void executionExecute_failingStep_producesFailureAuditLog() throws Exception {
        String appId = createApplicationAndGetId("audit-trail-exec-fail-app-" + UUID.randomUUID());
        String scenarioId = createScenarioAndGetId(appId, "exec-scenario-fail");
        createStepAndGetId(scenarioId, "step", 1, "/fail", 200);

        String executionId = submitExecutionWithoutWaiting(scenarioId);
        waitForTerminalStatus(executionId);

        waitForAuditEntry(AuditAction.EXECUTE, AuditModule.EXECUTION, AuditResult.FAILURE, executionId);
    }

    @Test
    void executionRetry_producesBothRetryAndExecuteAuditLogs() throws Exception {
        String appId = createApplicationAndGetId("audit-trail-retry-app-" + UUID.randomUUID());
        String scenarioId = createScenarioAndGetId(appId, "retry-scenario");
        createStepAndGetId(scenarioId, "step", 1, "/ok", 200);

        String originalExecutionId = submitExecutionWithoutWaiting(scenarioId);

        mockMvc.perform(post("/api/executions/" + originalExecutionId + "/retry").with(AS_SUPER_ADMIN))
                .andExpect(status().isAccepted());

        assertThat(hasAuditEntry(AuditAction.RETRY, AuditModule.EXECUTION, AuditResult.SUCCESS, originalExecutionId)).isTrue();
    }

    @Test
    void executionCancel_alreadyTerminated_producesFailureAuditLog() throws Exception {
        String appId = createApplicationAndGetId("audit-trail-cancel-fail-app-" + UUID.randomUUID());
        String scenarioId = createScenarioAndGetId(appId, "cancel-fail-scenario");
        createStepAndGetId(scenarioId, "step", 1, "/ok", 200);

        String executionId = submitExecutionWithoutWaiting(scenarioId);
        waitForTerminalStatus(executionId);

        mockMvc.perform(post("/api/executions/" + executionId + "/cancel").with(AS_SUPER_ADMIN))
                .andExpect(status().isConflict());

        assertThat(hasAuditEntry(AuditAction.CANCEL, AuditModule.EXECUTION, AuditResult.FAILURE, executionId)).isTrue();
    }

    @Test
    void executionCancel_runningExecution_producesSuccessAuditLog() throws Exception {
        String appId = createApplicationAndGetId("audit-trail-cancel-ok-app-" + UUID.randomUUID());
        String scenarioId = createScenarioAndGetId(appId, "cancel-ok-scenario");
        createStepAndGetId(scenarioId, "step", 1, "/slow", 200);

        String executionId = submitExecutionWithoutWaiting(scenarioId);
        waitForStatus(executionId, "RUNNING");

        mockMvc.perform(post("/api/executions/" + executionId + "/cancel").with(AS_SUPER_ADMIN))
                .andExpect(status().isOk());

        assertThat(hasAuditEntry(AuditAction.CANCEL, AuditModule.EXECUTION, AuditResult.SUCCESS, executionId)).isTrue();
    }

    // ------------------------------------------------------------
    // Aucune donnee sensible
    // ------------------------------------------------------------

    @Test
    void noAuditLogEverContainsSensitiveKeywords() {
        List<AuditLog> all = auditLogRepository.findAll();
        List<String> forbidden = List.of("authorization", "bearer ", "password", "refreshtoken", "clientsecret");

        for (AuditLog entry : all) {
            String description = entry.getDescription() != null ? entry.getDescription().toLowerCase() : "";
            for (String word : forbidden) {
                assertThat(description).as("AuditLog %s ne doit jamais contenir '%s'", entry.getId(), word)
                        .doesNotContain(word);
            }
        }
    }
}
