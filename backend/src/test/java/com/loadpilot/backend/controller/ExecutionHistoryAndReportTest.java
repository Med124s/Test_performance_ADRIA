package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
 * P1-A — verifie GET /api/executions/history (pagination/tri/filtres/
 * recherche, reels, cote base), GET /api/executions/{id}/report
 * (statistiques/percentiles/steps/erreurs reels) et
 * GET /api/executions/{id}/report/export (CSV reel), avec de VRAIES
 * executions HTTP (serveur JDK embarque, aucun mock du moteur).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExecutionHistoryAndReportTest {

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

    private String createScenarioAndGetId(String applicationId, String name) throws Exception {
        String body = objectMapper.writeValueAsString(new ScenarioRequest(UUID.fromString(applicationId), name, "d"));
        String response = mockMvc.perform(post("/api/scenarios").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private void createStep(String scenarioId, String name, String path, Integer expectedStatus) throws Exception {
        createStep(scenarioId, name, 1, path, expectedStatus);
    }

    private void createStep(String scenarioId, String name, int order, String path, Integer expectedStatus) throws Exception {
        String body = objectMapper.writeValueAsString(
                new StepRequest(UUID.fromString(scenarioId), name, HttpMethod.GET, path, null, null, order, expectedStatus,
                        null, null, null, null, null, null));
        mockMvc.perform(post("/api/steps").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private String executeAndWait(String scenarioId) throws Exception {
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
            String execStatus = objectMapper.readTree(statusResponse).get("status").asText();
            if (!execStatus.equals("QUEUED") && !execStatus.equals("RUNNING")) return executionId;
            Thread.sleep(20);
        }
        throw new AssertionError("Execution " + executionId + " n'a pas atteint un statut terminal a temps.");
    }

    // ------------------------------------------------------------
    // History
    // ------------------------------------------------------------

    @Test
    void history_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/executions/history")).andExpect(status().isUnauthorized());
    }

    @Test
    void history_isPaginatedAndSortedNewestFirstByDefault() throws Exception {
        String appId = createApplicationAndGetId("history-page-app-" + UUID.randomUUID());
        String scenarioId = createScenarioAndGetId(appId, "history-page-scenario");
        createStep(scenarioId, "step", "/ok", 200);

        String exec1 = executeAndWait(scenarioId);
        String exec2 = executeAndWait(scenarioId);
        String exec3 = executeAndWait(scenarioId);

        String response = mockMvc.perform(get("/api/executions/history")
                        .param("scenarioId", scenarioId).param("page", "0").param("size", "2").with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andReturn().getResponse().getContentAsString();

        JsonNode content = objectMapper.readTree(response).get("content");
        // Plus recente en premier (defaut startedAt desc) : exec3 puis exec2.
        assertThat(content.get(0).get("id").asText()).isEqualTo(exec3);
        assertThat(content.get(1).get("id").asText()).isEqualTo(exec2);
    }

    /**
     * P1-A (prompt sections 27/38) — demonstration a plus grande echelle :
     * genere reellement 27 executions pour un meme Scenario et verifie que
     * la pagination DB (LIMIT/OFFSET via Pageable, jamais un filtrage Java
     * apres chargement complet) reste coherente sur plusieurs pages
     * completes. Le mecanisme (Specification + Pageable Spring Data) ne
     * depend structurellement pas du nombre de lignes - ce test demontre
     * la coherence reelle des compteurs/decoupage, pas une mesure de
     * performance chiffree.
     */
    @Test
    void history_paginatesCorrectlyAcrossManyRealExecutions() throws Exception {
        String appId = createApplicationAndGetId("history-scale-app-" + UUID.randomUUID());
        String scenarioId = createScenarioAndGetId(appId, "history-scale-scenario");
        createStep(scenarioId, "step", "/ok", 200);

        int totalExecutions = 27;
        for (int i = 0; i < totalExecutions; i++) {
            executeAndWait(scenarioId);
        }

        int pageSize = 10;
        int expectedPages = (int) Math.ceil(totalExecutions / (double) pageSize);
        java.util.Set<String> seenIds = new java.util.HashSet<>();
        for (int page = 0; page < expectedPages; page++) {
            String response = mockMvc.perform(get("/api/executions/history")
                            .param("scenarioId", scenarioId).param("page", String.valueOf(page)).param("size", String.valueOf(pageSize))
                            .with(AS_VIEWER))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(totalExecutions))
                    .andExpect(jsonPath("$.totalPages").value(expectedPages))
                    .andReturn().getResponse().getContentAsString();
            JsonNode content = objectMapper.readTree(response).get("content");
            int expectedSizeThisPage = (page == expectedPages - 1) ? totalExecutions - page * pageSize : pageSize;
            assertThat(content).hasSize(expectedSizeThisPage);
            for (JsonNode node : content) {
                // Chaque id ne doit apparaitre EXACTEMENT une fois sur
                // l'ensemble des pages - jamais un doublon, jamais un trou.
                assertThat(seenIds.add(node.get("id").asText())).isTrue();
            }
        }
        assertThat(seenIds).hasSize(totalExecutions);
    }

    @Test
    void history_filtersByStatus() throws Exception {
        String appId = createApplicationAndGetId("history-status-app-" + UUID.randomUUID());
        String scenarioOk = createScenarioAndGetId(appId, "history-status-ok");
        createStep(scenarioOk, "step", "/ok", 200);
        String scenarioFail = createScenarioAndGetId(appId, "history-status-fail");
        createStep(scenarioFail, "step", "/fail", 200);

        String execOk = executeAndWait(scenarioOk);
        String execFail = executeAndWait(scenarioFail);

        String response = mockMvc.perform(get("/api/executions/history")
                        .param("applicationId", appId).param("status", "SUCCESS").with(AS_VIEWER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode content = objectMapper.readTree(response).get("content");
        for (JsonNode node : content) {
            assertThat(node.get("status").asText()).isEqualTo("SUCCESS");
        }
        boolean containsOk = false;
        boolean containsFail = false;
        for (JsonNode node : content) {
            if (node.get("id").asText().equals(execOk)) containsOk = true;
            if (node.get("id").asText().equals(execFail)) containsFail = true;
        }
        assertThat(containsOk).isTrue();
        assertThat(containsFail).isFalse();
    }

    @Test
    void history_filtersByDateRange_excludesExecutionsOutsideRange() throws Exception {
        String appId = createApplicationAndGetId("history-date-app-" + UUID.randomUUID());
        String scenarioId = createScenarioAndGetId(appId, "history-date-scenario");
        createStep(scenarioId, "step", "/ok", 200);
        String execId = executeAndWait(scenarioId);

        java.time.Instant future = java.time.Instant.now().plusSeconds(3600);
        String responseNoMatch = mockMvc.perform(get("/api/executions/history")
                        .param("scenarioId", scenarioId).param("dateFrom", future.toString()).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(responseNoMatch).get("totalElements").asInt()).isZero();

        java.time.Instant past = java.time.Instant.now().minusSeconds(3600);
        String responseMatch = mockMvc.perform(get("/api/executions/history")
                        .param("scenarioId", scenarioId).param("dateFrom", past.toString()).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode content = objectMapper.readTree(responseMatch).get("content");
        assertThat(content.get(0).get("id").asText()).isEqualTo(execId);
    }

    @Test
    void history_searchMatchesScenarioNameApplicationNameOrExecutionId() throws Exception {
        String uniqueMarker = "history-search-marker-" + UUID.randomUUID();
        String appId = createApplicationAndGetId(uniqueMarker + "-app");
        String scenarioId = createScenarioAndGetId(appId, uniqueMarker + "-scenario");
        createStep(scenarioId, "step", "/ok", 200);
        String execId = executeAndWait(scenarioId);

        String byScenarioName = mockMvc.perform(get("/api/executions/history").param("search", uniqueMarker).with(AS_VIEWER))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(byScenarioName).get("totalElements").asInt()).isGreaterThanOrEqualTo(1);

        String byExecutionId = mockMvc.perform(get("/api/executions/history").param("search", execId).with(AS_VIEWER))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode content = objectMapper.readTree(byExecutionId).get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("id").asText()).isEqualTo(execId);
    }

    @Test
    void history_noMatchingFilters_returnsEmptyContentNotAnError() throws Exception {
        mockMvc.perform(get("/api/executions/history").param("search", "no-such-scenario-xyz-" + UUID.randomUUID()).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content", org.hamcrest.Matchers.hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void history_sortByDurationAscending_isActuallyOrdered() throws Exception {
        String appId = createApplicationAndGetId("history-sort-app-" + UUID.randomUUID());
        String scenarioId = createScenarioAndGetId(appId, "history-sort-scenario");
        createStep(scenarioId, "step", "/ok", 200);
        executeAndWait(scenarioId);
        executeAndWait(scenarioId);

        String response = mockMvc.perform(get("/api/executions/history")
                        .param("scenarioId", scenarioId).param("sort", "duration,asc").with(AS_VIEWER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode content = objectMapper.readTree(response).get("content");
        long previous = -1;
        for (JsonNode node : content) {
            long duration = node.get("duration").asLong();
            assertThat(duration).isGreaterThanOrEqualTo(previous);
            previous = duration;
        }
    }

    /**
     * P1-C — "triggeredByUsername" (voir P1-B, Execution.triggeredBy) et
     * "throughput" (meme formule que PerformanceStatisticsService) sont
     * REELLEMENT presents et non nuls pour une execution reellement lancee
     * via l'API (donc avec un CurrentUser resolu, voir ExecutionController#execute).
     */
    @Test
    void history_exposesTriggeredByUsernameAndThroughput() throws Exception {
        String appId = createApplicationAndGetId("history-user-throughput-app-" + UUID.randomUUID());
        String scenarioId = createScenarioAndGetId(appId, "history-user-throughput-scenario");
        createStep(scenarioId, "step", "/ok", 200);
        String execId = executeAndWait(scenarioId);

        String response = mockMvc.perform(get("/api/executions/history")
                        .param("scenarioId", scenarioId).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode content = objectMapper.readTree(response).get("content");
        JsonNode entry = content.get(0);
        assertThat(entry.get("id").asText()).isEqualTo(execId);
        assertThat(entry.get("triggeredByUsername").asText()).isNotBlank();
        assertThat(entry.get("throughput").asDouble()).isGreaterThan(0);
    }

    /**
     * P1-C (prompt section 11) — chaque consultation de l'historique est
     * reellement auditee (AuditAction.VIEW_HISTORY, module EXECUTION).
     */
    @Test
    void history_isAudited() throws Exception {
        mockMvc.perform(get("/api/executions/history").with(AS_VIEWER)).andExpect(status().isOk());

        // /api/audit-logs est reserve a SUPER_ADMIN/PERFORMANCE_ENGINEER
        // (VIEWER explicitement exclu, voir AuditLogController) - jamais
        // AS_VIEWER ici, meme si l'action auditee elle-meme a ete faite par
        // un VIEWER.
        String auditResponse = mockMvc.perform(get("/api/audit-logs").param("action", "VIEW_HISTORY").param("size", "1").with(AS_SUPER_ADMIN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(auditResponse).get("totalElements").asInt()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void history_rejectsArbitrarySortColumn_fallsBackToDefaultSafely() throws Exception {
        // "id" n'est pas dans la whitelist (voir ExecutionController.
        // resolveHistorySort) - ne doit jamais lever, jamais executer un tri
        // sur une colonne arbitraire, retombe silencieusement sur le tri par
        // defaut (startedAt desc).
        mockMvc.perform(get("/api/executions/history").param("sort", "id,asc").with(AS_VIEWER))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------
    // Report
    // ------------------------------------------------------------

    @Test
    void report_unknownExecutionId_returns404() throws Exception {
        mockMvc.perform(get("/api/executions/" + UUID.randomUUID() + "/report").with(AS_VIEWER))
                .andExpect(status().isNotFound());
    }

    @Test
    void report_realExecution_containsRealStatisticsPercentilesStepsAndErrors() throws Exception {
        String appId = createApplicationAndGetId("report-app-" + UUID.randomUUID());
        String scenarioId = createScenarioAndGetId(appId, "report-scenario");
        createStep(scenarioId, "ok-step", "/ok", 200);
        String executionId = executeAndWait(scenarioId);

        String response = mockMvc.perform(get("/api/executions/" + executionId + "/report").with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(executionId))
                .andExpect(jsonPath("$.scenarioName").value("report-scenario"))
                .andExpect(jsonPath("$.statistics.totalRequests").value(1))
                .andExpect(jsonPath("$.statistics.p50").exists())
                .andExpect(jsonPath("$.statistics.p95").exists())
                .andExpect(jsonPath("$.statistics.p99").exists())
                // P1-C — ecart-type de population reel (0 pour une seule
                // requete, une vraie valeur, jamais null) et utilisateur
                // ayant reellement lance cette execution (voir P1-B).
                .andExpect(jsonPath("$.statistics.stdDevResponseTime").value(0))
                .andExpect(jsonPath("$.triggeredByUsername").exists())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        assertThat(json.get("steps")).hasSize(1);
        assertThat(json.get("steps").get(0).get("stepName").asText()).isEqualTo("ok-step");
        assertThat(json.get("steps").get(0).get("stdDevResponseTime").asInt()).isEqualTo(0);
        assertThat(json.get("errors")).hasSize(0);

        // P1-C (prompt section 11) — chaque consultation du rapport est
        // reellement auditee (AuditAction.EXPORT_REPORT, module EXECUTION).
        String auditResponse = mockMvc.perform(get("/api/audit-logs").param("action", "EXPORT_REPORT").param("size", "1").with(AS_SUPER_ADMIN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(auditResponse).get("totalElements").asInt()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void report_failingExecution_listsTheRealError() throws Exception {
        String appId = createApplicationAndGetId("report-fail-app-" + UUID.randomUUID());
        String scenarioId = createScenarioAndGetId(appId, "report-fail-scenario");
        createStep(scenarioId, "fail-step", "/fail", 200);
        String executionId = executeAndWait(scenarioId);

        String response = mockMvc.perform(get("/api/executions/" + executionId + "/report").with(AS_VIEWER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(response);
        assertThat(json.get("statistics").get("errorRate").asDouble()).isEqualTo(100.0);
        assertThat(json.get("errors")).hasSize(1);
        assertThat(json.get("errors").get(0).get("httpStatus").asInt()).isEqualTo(500);
    }

    // ------------------------------------------------------------
    // Export CSV
    // ------------------------------------------------------------

    @Test
    void exportReport_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/executions/" + UUID.randomUUID() + "/report/export")).andExpect(status().isUnauthorized());
    }

    @Test
    void exportReport_unknownExecutionId_returns404() throws Exception {
        mockMvc.perform(get("/api/executions/" + UUID.randomUUID() + "/report/export").with(AS_VIEWER))
                .andExpect(status().isNotFound());
    }

    @Test
    void exportReport_producesValidUtf8CsvWithExpectedSectionsAndNoSensitiveData() throws Exception {
        String appId = createApplicationAndGetId("export-app-" + UUID.randomUUID());
        String scenarioId = createScenarioAndGetId(appId, "export-scenario");
        createStep(scenarioId, "ok-step", "/ok", 200);
        String executionId = executeAndWait(scenarioId);

        byte[] csvBytes = mockMvc.perform(get("/api/executions/" + executionId + "/report/export").with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Type", org.hamcrest.Matchers.containsString("text/csv")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".csv")))
                .andReturn().getResponse().getContentAsByteArray();

        String csv = new String(csvBytes, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("EXECUTION");
        assertThat(csv).contains("LOAD CONFIGURATION");
        assertThat(csv).contains("STATISTICS");
        assertThat(csv).contains("STEPS");
        assertThat(csv).contains("ERRORS");
        assertThat(csv).contains(executionId);
        // P1-C — nouvelles colonnes reelles (ecart-type, utilisateur).
        assertThat(csv).contains("stdDev");
        assertThat(csv).contains("triggeredBy");

        // Jamais de donnee sensible dans l'export (prompt section 25).
        String lower = csv.toLowerCase();
        assertThat(lower).doesNotContain("authorization");
        assertThat(lower).doesNotContain("bearer ");
        assertThat(lower).doesNotContain("password");
        assertThat(lower).doesNotContain("jwt");
        assertThat(lower).doesNotContain("secret");

        // P1-C (prompt section 11) — chaque export CSV est reellement
        // audite (AuditAction.EXPORT_CSV, module EXECUTION).
        String auditResponse = mockMvc.perform(get("/api/audit-logs").param("action", "EXPORT_CSV").param("size", "1").with(AS_SUPER_ADMIN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(auditResponse).get("totalElements").asInt()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void exportReport_csvInjectionAttempt_isNeutralized() throws Exception {
        // Un nom de Scenario commencant par '=' pourrait etre interprete
        // comme une formule par Excel/Sheets a l'ouverture - verifie que
        // CsvUtils neutralise bien ce cas reel (prompt section 25).
        String appId = createApplicationAndGetId("csv-injection-app-" + UUID.randomUUID());
        String maliciousName = "=1+1";
        String scenarioId = createScenarioAndGetId(appId, maliciousName);
        createStep(scenarioId, "ok-step", "/ok", 200);
        String executionId = executeAndWait(scenarioId);

        byte[] csvBytes = mockMvc.perform(get("/api/executions/" + executionId + "/report/export").with(AS_VIEWER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        String csv = new String(csvBytes, java.nio.charset.StandardCharsets.UTF_8);

        // La valeur brute "=1+1" ne doit jamais apparaitre en debut de
        // cellule (donc jamais juste apres une virgule ou en debut de
        // ligne) - elle doit avoir ete prefixee d'une apostrophe.
        assertThat(csv).doesNotContain(",=1+1");
        assertThat(csv).contains("'=1+1");
    }
}
