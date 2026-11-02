package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.dto.request.ApplicationRequest;
import com.loadpilot.backend.dto.request.ExecutionRequest;
import com.loadpilot.backend.dto.request.ScenarioRequest;
import com.loadpilot.backend.dto.request.StepRequest;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.enums.HttpMethod;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.service.impl.ExecutionTransactionHelper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

/** Module Executions de bout en bout, avec de vraies requetes HTTP vers un serveur embarque ; seul JwtDecoder est simule. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExecutionControllerTest {

    private static HttpServer targetServer;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ExecutionRepository executionRepository;

    @MockBean
    private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_SUPER_ADMIN =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
    private static final RequestPostProcessor AS_ENGINEER =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_PERFORMANCE_ENGINEER"));
    private static final RequestPostProcessor AS_VIEWER =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"));

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

    /** Soumet une execution (attend 202/QUEUED) puis attend qu'elle atteigne un statut terminal. */
    private String executeScenarioAndGetId(String scenarioId) throws Exception {
        String executionId = submitExecutionWithoutWaiting(scenarioId);
        waitForTerminalStatus(executionId);
        return executionId;
    }

    /** Soumet une execution et renvoie son id sans attendre ; le statut renvoye peut etre QUEUED ou RUNNING. */
    private String submitExecutionWithoutWaiting(String scenarioId) throws Exception {
        String body = objectMapper.writeValueAsString(new ExecutionRequest(UUID.fromString(scenarioId)));
        String response = mockMvc.perform(post("/api/executions").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String returnedStatus = objectMapper.readTree(response).get("status").asText();
        assertThat(returnedStatus).isIn("QUEUED", "RUNNING");
        return objectMapper.readTree(response).get("id").asText();
    }

    private String currentStatus(String executionId) throws Exception {
        String response = mockMvc.perform(get("/api/executions/" + executionId).with(AS_VIEWER))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("status").asText();
    }

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

    /** Scenario a une etape reussie (/ok). */
    private String scenarioWithSuccessfulStep(String prefix) throws Exception {
        String appId = createApplicationAndGetId(prefix + "-app");
        String scenarioId = createScenarioAndGetId(appId, prefix + "-scenario");
        createStep(scenarioId, prefix + "-step", 1, "/ok", 200);
        return scenarioId;
    }

    /** Scenario a une etape en echec (/fail). */
    private String scenarioWithFailingStep(String prefix) throws Exception {
        String appId = createApplicationAndGetId(prefix + "-app");
        String scenarioId = createScenarioAndGetId(appId, prefix + "-scenario");
        createStep(scenarioId, prefix + "-step", 1, "/fail", 200);
        return scenarioId;
    }

    // ------------------------------------------------------------
    // Securite
    // ------------------------------------------------------------

    @Test
    void list_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/executions")).andExpect(status().isUnauthorized());
    }

    @Test
    void execute_withoutToken_returns401() throws Exception {
        String body = objectMapper.writeValueAsString(new ExecutionRequest(UUID.randomUUID()));
        mockMvc.perform(post("/api/executions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void viewerRole_canListButNotExecuteCancelOrRetry() throws Exception {
        String scenarioId = scenarioWithSuccessfulStep("viewer-sec");
        String executionId = executeScenarioAndGetId(scenarioId);

        mockMvc.perform(get("/api/executions").with(AS_VIEWER)).andExpect(status().isOk());

        String body = objectMapper.writeValueAsString(new ExecutionRequest(UUID.fromString(scenarioId)));
        mockMvc.perform(post("/api/executions").with(AS_VIEWER).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/executions/" + executionId + "/cancel").with(AS_VIEWER))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/executions/" + executionId + "/retry").with(AS_VIEWER))
                .andExpect(status().isForbidden());
    }

    @Test
    void engineerRole_canExecute() throws Exception {
        String scenarioId = scenarioWithSuccessfulStep("engineer-sec");
        String body = objectMapper.writeValueAsString(new ExecutionRequest(UUID.fromString(scenarioId)));
        mockMvc.perform(post("/api/executions").with(AS_ENGINEER).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());
    }

    @Test
    void superAdminRole_canExecute() throws Exception {
        String scenarioId = scenarioWithSuccessfulStep("admin-sec");
        String body = objectMapper.writeValueAsString(new ExecutionRequest(UUID.fromString(scenarioId)));
        mockMvc.perform(post("/api/executions").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());
    }

    // ------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------

    @Test
    void execute_missingScenarioId_returns400() throws Exception {
        String body = "{\"scenarioId\":null}";
        mockMvc.perform(post("/api/executions").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void execute_nonexistentScenario_returns404() throws Exception {
        String body = objectMapper.writeValueAsString(new ExecutionRequest(UUID.randomUUID()));
        mockMvc.perform(post("/api/executions").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void execute_scenarioWithoutSteps_returns409() throws Exception {
        String appId = createApplicationAndGetId("no-steps-app");
        String scenarioId = createScenarioAndGetId(appId, "no-steps-scenario");

        String body = objectMapper.writeValueAsString(new ExecutionRequest(UUID.fromString(scenarioId)));
        mockMvc.perform(post("/api/executions").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    // ------------------------------------------------------------
    // CRUD / liste / filtre
    // ------------------------------------------------------------

    @Test
    void execute_createsExecutionWithSuccessStatus() throws Exception {
        String scenarioId = scenarioWithSuccessfulStep("crud-create");

        String executionId = executeScenarioAndGetId(scenarioId);

        mockMvc.perform(get("/api/executions/" + executionId).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(executionId))
                .andExpect(jsonPath("$.scenarioId").value(scenarioId))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.totalSteps").value(1))
                .andExpect(jsonPath("$.successfulSteps").value(1))
                .andExpect(jsonPath("$.failedSteps").value(0))
                .andExpect(jsonPath("$.startedAt").exists())
                .andExpect(jsonPath("$.finishedAt").exists())
                .andExpect(jsonPath("$.duration").isNumber());
    }

    @Test
    void getById_returnsDetailWithStepResults() throws Exception {
        String scenarioId = scenarioWithSuccessfulStep("crud-get");
        String executionId = executeScenarioAndGetId(scenarioId);

        mockMvc.perform(get("/api/executions/" + executionId).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(executionId))
                .andExpect(jsonPath("$.results", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.results[0].httpStatus").value(200))
                .andExpect(jsonPath("$.results[0].success").value(true))
                .andExpect(jsonPath("$.results[0].method").value("GET"))
                .andExpect(jsonPath("$.results[0].responseTime").isNumber());
    }

    @Test
    void list_includesCreatedExecution() throws Exception {
        String scenarioId = scenarioWithSuccessfulStep("crud-list");
        String executionId = executeScenarioAndGetId(scenarioId);

        mockMvc.perform(get("/api/executions").with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + executionId + "')]").exists());
    }

    @Test
    void listByScenario_returnsOnlyExecutionsOfThatScenario() throws Exception {
        String scenarioA = scenarioWithSuccessfulStep("filter-a");
        String scenarioB = scenarioWithSuccessfulStep("filter-b");
        String execA = executeScenarioAndGetId(scenarioA);
        String execB = executeScenarioAndGetId(scenarioB);

        mockMvc.perform(get("/api/executions").param("scenarioId", scenarioA).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + execA + "')]").exists())
                .andExpect(jsonPath("$[?(@.id=='" + execB + "')]").isEmpty());
    }

    @Test
    void getById_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/api/executions/" + UUID.randomUUID()).with(AS_VIEWER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // ------------------------------------------------------------
    // Resultats / statuts
    // ------------------------------------------------------------

    @Test
    void failingStep_testRanToTheEnd_thresholdsExceededWithError() throws Exception {
        // Le test est alle au bout : seuils depasses, pas « Echouee ».
        String scenarioId = scenarioWithFailingStep("status-failed");
        String executionId = executeScenarioAndGetId(scenarioId);

        mockMvc.perform(get("/api/executions/" + executionId).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("THRESHOLDS_EXCEEDED"))
                .andExpect(jsonPath("$.errorMessage").value(org.hamcrest.Matchers.startsWith("Seuils dépassés : taux d'erreurs 100 % > 1 %")))
                .andExpect(jsonPath("$.successfulSteps").value(0))
                .andExpect(jsonPath("$.failedSteps").value(1))
                .andExpect(jsonPath("$.results[0].success").value(false))
                .andExpect(jsonPath("$.results[0].error").exists());
    }

    @Test
    void totalSteps_reflectsFullScenarioEvenWhenExecutionStopsEarly() throws Exception {
        String appId = createApplicationAndGetId("total-steps-app");
        String scenarioId = createScenarioAndGetId(appId, "total-steps-scenario");
        createStep(scenarioId, "step-1-fails", 1, "/fail", 200);
        createStep(scenarioId, "step-2-never-runs", 2, "/ok", 200);

        String executionId = executeScenarioAndGetId(scenarioId);

        mockMvc.perform(get("/api/executions/" + executionId).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSteps").value(2))
                .andExpect(jsonPath("$.successfulSteps").value(0))
                .andExpect(jsonPath("$.failedSteps").value(1))
                .andExpect(jsonPath("$.results", org.hamcrest.Matchers.hasSize(1)));
    }

    @Test
    void multipleSuccessfulSteps_resultsAreInExecutionOrder() throws Exception {
        String appId = createApplicationAndGetId("order-app");
        String scenarioId = createScenarioAndGetId(appId, "order-scenario");
        createStep(scenarioId, "step-a", 1, "/ok", 200);
        createStep(scenarioId, "step-b", 2, "/ok", 200);

        String executionId = executeScenarioAndGetId(scenarioId);
        String response = mockMvc.perform(get("/api/executions/" + executionId).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode results = objectMapper.readTree(response).get("results");
        assertThat(results.size()).isEqualTo(2);
        assertThat(results.get(0).get("stepName").asText()).isEqualTo("step-a");
        assertThat(results.get(1).get("stepName").asText()).isEqualTo("step-b");
    }

    @Test
    void executionTransactionHelper_setsQueuedThenRunningStatus(
            @Autowired ExecutionTransactionHelper transactionHelper) throws Exception {
        String scenarioId = scenarioWithSuccessfulStep("running-start");

        var prepared = transactionHelper.prepareAndStart(UUID.fromString(scenarioId), null, null);
        Execution queued = executionRepository.findById(prepared.executionId()).orElseThrow();

        assertThat(queued.getStatus()).isEqualTo(ExecutionStatus.QUEUED);
        assertThat(queued.getFinishedAt()).isNull();
        assertThat(queued.getDuration()).isNull();

        transactionHelper.markRunning(prepared.executionId());
        Execution running = executionRepository.findById(prepared.executionId()).orElseThrow();

        assertThat(running.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(running.getFinishedAt()).isNull();
        assertThat(running.getDuration()).isNull();
    }

    // ------------------------------------------------------------
    // Retry
    // ------------------------------------------------------------

    @Test
    void retry_createsNewExecution_andLeavesOldOneUnchanged() throws Exception {
        String scenarioId = scenarioWithSuccessfulStep("retry-test");
        String originalId = executeScenarioAndGetId(scenarioId);

        String originalBefore = mockMvc.perform(get("/api/executions/" + originalId).with(AS_VIEWER))
                .andReturn().getResponse().getContentAsString();

        String retryResponse = mockMvc.perform(post("/api/executions/" + originalId + "/retry").with(AS_SUPER_ADMIN))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String newId = objectMapper.readTree(retryResponse).get("id").asText();

        assertThat(newId).isNotEqualTo(originalId);
        waitForTerminalStatus(newId);

        String originalAfter = mockMvc.perform(get("/api/executions/" + originalId).with(AS_VIEWER))
                .andReturn().getResponse().getContentAsString();
        assertThat(originalAfter).isEqualTo(originalBefore);
    }

    // ------------------------------------------------------------
    // Cancel
    // ------------------------------------------------------------

    @Test
    void cancel_alreadyTerminatedExecution_returns409() throws Exception {
        String scenarioId = scenarioWithSuccessfulStep("cancel-terminated");
        String executionId = executeScenarioAndGetId(scenarioId);

        mockMvc.perform(post("/api/executions/" + executionId + "/cancel").with(AS_SUPER_ADMIN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void cancel_runningExecution_transitionsToCancelled() throws Exception {
        String appId = createApplicationAndGetId("cancel-running-app");
        String scenarioId = createScenarioAndGetId(appId, "cancel-running-scenario");
        createStep(scenarioId, "slow-step", 1, "/slow", 200);

        String executionId = submitExecutionWithoutWaiting(scenarioId);
        waitForStatus(executionId, "RUNNING");

        mockMvc.perform(post("/api/executions/" + executionId + "/cancel").with(AS_SUPER_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));

        waitForTerminalStatus(executionId);

        mockMvc.perform(get("/api/executions/" + executionId).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.finishedAt").exists());
    }

    /** Plusieurs annulations simultanees de la meme execution donnent un etat final coherent. */
    @Test
    void concurrentCancelCalls_onSameRunningExecution_neverProduceAnIncoherentFinalState() throws Exception {
        String appId = createApplicationAndGetId("concurrent-cancel-app-" + UUID.randomUUID());
        String scenarioId = createScenarioAndGetId(appId, "concurrent-cancel-scenario");
        createStep(scenarioId, "slow-step", 1, "/slow", 200);

        String executionId = submitExecutionWithoutWaiting(scenarioId);
        waitForStatus(executionId, "RUNNING");

        int threadCount = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return mockMvc.perform(post("/api/executions/" + executionId + "/cancel").with(AS_SUPER_ADMIN))
                        .andReturn().getResponse().getStatus();
            }));
        }
        ready.await();
        go.countDown();

        long successCount = 0;
        for (Future<Integer> f : futures) {
            int status = f.get();
            // Seuls 200 (annulation demandee) ou 409 (deja terminee) sont attendus.
            assertThat(status).isIn(200, 409);
            if (status == 200) successCount++;
        }
        pool.shutdown();

        // Au moins un appel a demande l'annulation.
        assertThat(successCount).isGreaterThanOrEqualTo(1);

        waitForTerminalStatus(executionId);
        // Un seul etat final, coherent.
        String finalDetail = mockMvc.perform(get("/api/executions/" + executionId).with(AS_VIEWER))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andReturn().getResponse().getContentAsString();
        String finishedAt = objectMapper.readTree(finalDetail).get("finishedAt").asText();
        assertThat(finishedAt).isNotBlank();
    }

    // ---- Isolation entre executions simultanees ----

    private String createScenarioWithVirtualUsers(String applicationId, String name, int virtualUsers) throws Exception {
        String body = objectMapper.writeValueAsString(
                new ScenarioRequest(UUID.fromString(applicationId), name, "Description", virtualUsers, 0, null, null, 0, null, null));
        String response = mockMvc.perform(post("/api/scenarios").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    /** 3 executions (5 utilisateurs virtuels chacune) lancees en meme temps ne melangent ni resultats ni statuts. */
    @Test
    void threeExecutions_runningSimultaneously_remainCompletelyIsolated() throws Exception {
        String appId = createApplicationAndGetId("isolation-app-" + UUID.randomUUID());
        String scenarioA = createScenarioWithVirtualUsers(appId, "isolation-scenario-a", 5);
        createStep(scenarioA, "step-a", 1, "/ok", 200);
        String scenarioB = createScenarioWithVirtualUsers(appId, "isolation-scenario-b", 5);
        createStep(scenarioB, "step-b", 1, "/ok", 200);
        String scenarioC = createScenarioWithVirtualUsers(appId, "isolation-scenario-c", 5);
        createStep(scenarioC, "step-c", 1, "/ok", 200);

        // Lancees quasi simultanement, sans attendre entre chacune.
        String execA = submitExecutionWithoutWaiting(scenarioA);
        String execB = submitExecutionWithoutWaiting(scenarioB);
        String execC = submitExecutionWithoutWaiting(scenarioC);

        waitForTerminalStatus(execA);
        waitForTerminalStatus(execB);
        waitForTerminalStatus(execC);

        String detailA = mockMvc.perform(get("/api/executions/" + execA).with(AS_VIEWER))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String detailB = mockMvc.perform(get("/api/executions/" + execB).with(AS_VIEWER))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String detailC = mockMvc.perform(get("/api/executions/" + execC).with(AS_VIEWER))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        JsonNode jsonA = objectMapper.readTree(detailA);
        JsonNode jsonB = objectMapper.readTree(detailB);
        JsonNode jsonC = objectMapper.readTree(detailC);

        // 5 resultats chacune (5 utilisateurs virtuels, 1 passage), tous reussis, tous sur sa propre etape.
        for (JsonNode json : List.of(jsonA, jsonB, jsonC)) {
            assertThat(json.get("status").asText()).isEqualTo("SUCCESS");
            assertThat(json.get("successfulSteps").asInt()).isEqualTo(5);
            assertThat(json.get("results")).hasSize(5);
        }
        String stepNameA = jsonA.get("results").get(0).get("stepName").asText();
        String stepNameB = jsonB.get("results").get(0).get("stepName").asText();
        String stepNameC = jsonC.get("results").get(0).get("stepName").asText();
        assertThat(stepNameA).isEqualTo("step-a");
        assertThat(stepNameB).isEqualTo("step-b");
        assertThat(stepNameC).isEqualTo("step-c");
        // Les resultats de A referencent l'etape de A.
        for (JsonNode result : jsonA.get("results")) {
            assertThat(result.get("stepName").asText()).isEqualTo("step-a");
        }
        for (JsonNode result : jsonB.get("results")) {
            assertThat(result.get("stepName").asText()).isEqualTo("step-b");
        }
        for (JsonNode result : jsonC.get("results")) {
            assertThat(result.get("stepName").asText()).isEqualTo("step-c");
        }

        // Statuts distincts.
        assertThat(execA).isNotEqualTo(execB);
        assertThat(execB).isNotEqualTo(execC);
    }

    // ---- Parametres de charge figes au lancement ----

    // Modifier le scenario pendant une execution est refuse ; l'execution garde ses parametres.
    @Test
    void modifyingScenario_whileExecutionIsRunning_isRefused_andFrozenParametersStayUnchanged() throws Exception {
        String appId = createApplicationAndGetId("immutability-app-" + UUID.randomUUID());
        String scenarioId = createScenarioAndGetId(appId, "immutability-scenario");
        createStep(scenarioId, "slow-step", 1, "/slow", 200);

        // Parametres figes au lancement : 1 utilisateur virtuel, ramp-up 0.
        String executionId = submitExecutionWithoutWaiting(scenarioId);
        waitForStatus(executionId, "RUNNING");

        // Modification PENDANT l'execution : refusee avec un message clair.
        String updateBody = objectMapper.writeValueAsString(
                new ScenarioRequest(UUID.fromString(appId), "immutability-scenario-renamed", "Description", 99, 5, null, null, 0, null, null));
        mockMvc.perform(put("/api/scenarios/" + scenarioId)
                        .with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Une exécution de ce scénario est en cours : attendez sa fin ou annulez-la avant de le modifier."));

        waitForTerminalStatus(executionId);

        // Une fois l'execution terminee, la modification est acceptee.
        mockMvc.perform(put("/api/scenarios/" + scenarioId)
                        .with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isOk());

        // L'execution garde les parametres copies au lancement.
        mockMvc.perform(get("/api/executions/" + executionId).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.virtualUsers").value(1))
                .andExpect(jsonPath("$.rampUpSeconds").value(0));

        // Le scenario contient la modification.
        mockMvc.perform(get("/api/scenarios/" + scenarioId).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.virtualUsers").value(99))
                .andExpect(jsonPath("$.rampUpSeconds").value(5));

        // Une nouvelle execution recoit les nouveaux parametres.
        String newExecutionId = submitExecutionWithoutWaiting(scenarioId);
        mockMvc.perform(get("/api/executions/" + newExecutionId).with(AS_VIEWER))
                .andExpect(jsonPath("$.virtualUsers").value(99))
                .andExpect(jsonPath("$.rampUpSeconds").value(5));
        // Annulee aussitot pour ne pas laisser tourner 99 utilisateurs virtuels.
        mockMvc.perform(post("/api/executions/" + newExecutionId + "/cancel").with(AS_SUPER_ADMIN));
        waitForTerminalStatus(newExecutionId);
    }
}
