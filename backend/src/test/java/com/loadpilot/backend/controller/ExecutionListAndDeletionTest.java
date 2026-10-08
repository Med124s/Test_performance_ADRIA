package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.repository.AuditLogRepository;
import com.loadpilot.backend.repository.ExecutionStepResultRepository;
import com.loadpilot.backend.repository.MetricRepository;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Page Executions : GET /page (filtres combines, pagination, compteurs par statut suivant les
 * filtres, jamais audite) ; suppression simple et groupee (resultats et metriques supprimes,
 * executions actives refusees avec succes partiel, droits, audit).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExecutionListAndDeletionTest {

    private static HttpServer server;
    private static String targetUrl;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ExecutionStepResultRepository resultRepository;
    @Autowired
    private MetricRepository metricRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @MockBean
    private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_ENGINEER = jwt().authorities(new SimpleGrantedAuthority("ROLE_PERFORMANCE_ENGINEER"));
    private static final RequestPostProcessor AS_VIEWER = jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"));

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", ex -> {
            try {
                Thread.sleep(ex.getRequestURI().getPath().equals("/slow") ? 200 : 5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(ex.getRequestURI().getPath().equals("/fail") ? 500 : 200, bytes.length);
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

    private MvcResult perform(MockHttpServletRequestBuilder builder, Object body, RequestPostProcessor as) throws Exception {
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        return mockMvc.perform(builder.with(as)).andReturn();
    }

    private JsonNode call(MockHttpServletRequestBuilder builder, Object body) throws Exception {
        String json = perform(builder, body, AS_ENGINEER).getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.isBlank() ? null : objectMapper.readTree(json);
    }

    private String scenario(String appId, String name, String path, Integer durationSeconds) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("applicationId", appId, "name", name, "virtualUsers", 1, "rampUpSeconds", 0, "thinkTimeMs", 0));
        if (durationSeconds != null) body.put("durationSeconds", durationSeconds);
        else body.put("iterations", 2);
        String id = call(post("/api/scenarios"), body).get("id").asText();
        call(post("/api/steps"), Map.of("scenarioId", id, "name", "Appel", "method", "GET", "url", path, "order", 1));
        return id;
    }

    private String runToEnd(String scenarioId) throws Exception {
        String executionId = call(post("/api/executions"), Map.of("scenarioId", scenarioId)).get("id").asText();
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            String status = call(get("/api/executions/" + executionId + "/status"), null).get("status").asText();
            if (List.of("SUCCESS", "THRESHOLDS_EXCEEDED", "FAILED", "CANCELLED").contains(status)) break;
            Thread.sleep(100);
        }
        // Laisse la tache asynchrone finir d'enregistrer les metriques (sinon 409 « termine l'enregistrement »).
        Thread.sleep(500);
        return executionId;
    }

    @Test
    void pageFiltersCombine_countsFollowFilters_andIsNotAudited() throws Exception {
        String appId = call(post("/api/applications"), Map.of("name", "liste-" + UUID.randomUUID(), "url", targetUrl)).get("id").asText();
        String ok = scenario(appId, "Liste OK", "/ok", null);
        String ko = scenario(appId, "Liste KO", "/fail", null);
        runToEnd(ok);
        runToEnd(ok);
        runToEnd(ko);

        long auditBefore = auditLogRepository.count();
        JsonNode all = call(get("/api/executions/page?applicationId=" + appId + "&size=5"), null);
        assertThat(all.get("totalElements").asLong()).isEqualTo(3);
        assertThat(all.get("counts").get("total").asLong()).isEqualTo(3);
        assertThat(all.get("counts").get("success").asLong()).isEqualTo(2);
        // /fail repond 500 : le test va au bout, seuil de taux d'erreurs depasse (pas « Echouee »).
        assertThat(all.get("counts").get("thresholdsExceeded").asLong()).isEqualTo(1);
        assertThat(all.get("counts").get("failed").asLong()).isZero();

        JsonNode byScenario = call(get("/api/executions/page?applicationId=" + appId + "&scenarioId=" + ok + "&status=SUCCESS"), null);
        assertThat(byScenario.get("totalElements").asLong()).isEqualTo(2);
        assertThat(byScenario.get("content").findValuesAsText("scenarioName")).containsOnly("Liste OK");
        assertThat(byScenario.get("counts").get("failed").asLong()).isZero();

        JsonNode search = call(get("/api/executions/page").param("applicationId", appId).param("search", "liste ko"), null);
        assertThat(search.get("totalElements").asLong()).isEqualTo(1);
        assertThat(search.get("counts").get("thresholdsExceeded").asLong()).isEqualTo(1);

        JsonNode paged = call(get("/api/executions/page?applicationId=" + appId + "&size=5&page=0"), null);
        assertThat(paged.get("size").asInt()).isEqualTo(5);

        // Periode (bornes incluses, sur startedAt) : compteurs et lignes suivent le filtre.
        String future = java.time.Instant.now().plusSeconds(3600).toString();
        JsonNode none = call(get("/api/executions/page").param("applicationId", appId).param("dateFrom", future), null);
        assertThat(none.get("totalElements").asLong()).isZero();
        assertThat(none.get("counts").get("total").asLong()).isZero();
        String hourAgo = java.time.Instant.now().minusSeconds(3600).toString();
        JsonNode inRange = call(get("/api/executions/page").param("applicationId", appId).param("dateFrom", hourAgo)
                .param("dateTo", future), null);
        assertThat(inRange.get("totalElements").asLong()).isEqualTo(3);
        assertThat(inRange.get("counts").get("success").asLong()).isEqualTo(2);
        JsonNode before = call(get("/api/executions/page").param("applicationId", appId).param("dateTo", hourAgo), null);
        assertThat(before.get("totalElements").asLong()).isZero();

        // Tri par statut / duree (liste blanche) : l'ordre suit le tri demande.
        JsonNode byStatus = call(get("/api/executions/page").param("applicationId", appId).param("sort", "status,asc"), null);
        assertThat(byStatus.get("content").get(0).get("status").asText()).isEqualTo("SUCCESS");
        JsonNode byStatusDesc = call(get("/api/executions/page").param("applicationId", appId).param("sort", "status,desc"), null);
        assertThat(byStatusDesc.get("content").get(0).get("status").asText()).isEqualTo("THRESHOLDS_EXCEEDED");
        assertThat(auditLogRepository.count()).as("la liste n'est pas auditee").isEqualTo(auditBefore);

        // P95 de chaque ligne : celui du rapport (resume enregistre, calcule a la fin ou en arriere-plan).
        JsonNode withP95 = null;
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            withP95 = call(get("/api/executions/page").param("applicationId", appId), null);
            boolean complete = true;
            for (JsonNode r : withP95.get("content")) complete &= !r.get("p95Ms").isNull();
            if (complete) break;
            Thread.sleep(200);
        }
        for (JsonNode r : withP95.get("content")) {
            JsonNode report = call(get("/api/executions/" + r.get("id").asText() + "/report"), null);
            assertThat(r.get("p95Ms").asLong()).isEqualTo(report.get("statistics").get("p95").asLong());
        }
    }

    @Test
    void deleteFinishedExecution_removesResultsAndMetrics_audited() throws Exception {
        String appId = call(post("/api/applications"), Map.of("name", "suppr-" + UUID.randomUUID(), "url", targetUrl)).get("id").asText();
        String executionId = runToEnd(scenario(appId, "A supprimer", "/ok", null));
        UUID id = UUID.fromString(executionId);
        assertThat(resultRepository.findByExecutionIdOrderByTimestampAsc(id)).isNotEmpty();

        JsonNode impact = call(post("/api/executions/deletion-impact"), Map.of("ids", List.of(executionId)));
        assertThat(impact.get("executions").asLong()).isEqualTo(1);
        assertThat(impact.get("results").asLong()).isEqualTo(2);
        assertThat(impact.get("metrics").asLong()).isPositive();
        assertThat(impact.get("active").asLong()).isZero();

        assertThat(perform(delete("/api/executions/" + executionId), null, AS_VIEWER).getResponse().getStatus()).isEqualTo(403);
        assertThat(perform(delete("/api/executions/" + executionId), null, AS_ENGINEER).getResponse().getStatus()).isEqualTo(204);
        assertThat(perform(get("/api/executions/" + executionId), null, AS_ENGINEER).getResponse().getStatus()).isEqualTo(404);
        assertThat(resultRepository.findByExecutionIdOrderByTimestampAsc(id)).isEmpty();
        assertThat(metricRepository.findAll().stream().noneMatch(m -> m.getExecution().getId().equals(id))).isTrue();
        assertThat(auditLogRepository.findAll().stream().anyMatch(a -> a.getAction() == AuditAction.DELETE
                && executionId.equals(a.getTargetId()))).isTrue();
        assertThat(perform(delete("/api/executions/" + executionId), null, AS_ENGINEER).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void bulkDelete_refusesActiveExecution_deletesTheOthers() throws Exception {
        String appId = call(post("/api/applications"), Map.of("name", "groupe-" + UUID.randomUUID(), "url", targetUrl)).get("id").asText();
        String finished1 = runToEnd(scenario(appId, "Fini 1", "/ok", null));
        String finished2 = runToEnd(scenario(appId, "Fini 2", "/fail", null));
        String running = call(post("/api/executions"), Map.of("scenarioId", scenario(appId, "Long", "/slow", 20))).get("id").asText();

        // Page Rapports : seulement les executions terminees (l'execution en cours n'y figure pas), lisible par un VIEWER.
        JsonNode reports = objectMapper.readTree(perform(get("/api/executions/page").param("applicationId", appId)
                .param("finished", "true").param("size", "10"), null, AS_VIEWER).getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(reports.get("totalElements").asLong()).isEqualTo(2);
        assertThat(reports.get("content").findValuesAsText("id")).containsExactlyInAnyOrder(finished1, finished2);
        assertThat(reports.get("counts").get("total").asLong()).isEqualTo(2);
        assertThat(reports.get("counts").get("running").asLong()).isZero();
        JsonNode all = call(get("/api/executions/page").param("applicationId", appId), null);
        assertThat(all.get("totalElements").asLong()).isEqualTo(3);

        assertThat(perform(delete("/api/executions/" + running), null, AS_ENGINEER).getResponse().getStatus()).isEqualTo(409);
        JsonNode impact = call(post("/api/executions/deletion-impact"), Map.of("ids", List.of(finished1, finished2, running)));
        assertThat(impact.get("active").asLong()).isEqualTo(1);

        JsonNode result = call(post("/api/executions/bulk-delete"), Map.of("ids", List.of(finished1, finished2, running)));
        assertThat(result.get("succeeded").asInt()).isEqualTo(2);
        assertThat(result.get("failed").asInt()).isEqualTo(1);
        JsonNode refused = null;
        for (JsonNode item : result.get("results")) if (!item.get("success").asBoolean()) refused = item;
        assertThat(refused.get("id").asText()).isEqualTo(running);
        assertThat(refused.get("message").asText()).contains("annulez-la");
        assertThat(perform(get("/api/executions/" + finished1), null, AS_ENGINEER).getResponse().getStatus()).isEqualTo(404);
        assertThat(perform(get("/api/executions/" + running), null, AS_ENGINEER).getResponse().getStatus()).isEqualTo(200);

        call(post("/api/executions/" + running + "/cancel"), null);
        assertThat(perform(post("/api/executions/bulk-delete"), Map.of("ids", List.of(finished1)), AS_VIEWER).getResponse().getStatus()).isEqualTo(403);
    }
}
