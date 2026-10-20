package com.loadpilot.backend.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.entity.AuditLog;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.repository.AuditLogRepository;
import com.loadpilot.backend.repository.ExecutionReportSnapshotRepository;
import com.loadpilot.backend.service.AppSettingsService;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Conservation des resultats detailles : rapport fige a la fin de l'execution, purge apres 90 jours
 * (resultats) et 30 jours (en-tetes / corps), rapport / analyse / extremes / comparaison identiques
 * apres purge avec la mention, option « Conserver », executions en cours jamais purgees, entree
 * « Système » au journal seulement si quelque chose a ete supprime, reglages et droits.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResultsRetentionTest {

    private static HttpServer server;
    private static String targetUrl;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ResultsRetentionJob job;
    @Autowired private ReportSnapshotService snapshotService;
    @Autowired private ExecutionReportSnapshotRepository snapshotRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private AppSettingsService settings;
    @MockBean private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_ENGINEER = jwt().jwt(j -> j.claim("preferred_username", "engineer-test"))
            .authorities(new SimpleGrantedAuthority("ROLE_PERFORMANCE_ENGINEER"));
    private static final RequestPostProcessor AS_VIEWER = jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"));
    private static final RequestPostProcessor AS_ADMIN = jwt().jwt(j -> j.claim("preferred_username", "admin-test"))
            .authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", ex -> {
            try {
                Thread.sleep(ex.getRequestURI().getPath().equals("/slow") ? 30 : 3);
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

    @AfterEach
    void resetSettings() {
        settings.set(AppSettingsService.RESULTS_RETENTION_DAYS, "90", "test");
        settings.set(AppSettingsService.REQUEST_DETAILS_RETENTION_DAYS, "30", "test");
    }

    // ------------------------------------------------------------------ outils

    private MvcResult perform(MockHttpServletRequestBuilder builder, Object body, RequestPostProcessor as) throws Exception {
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        return mockMvc.perform(builder.with(as)).andReturn();
    }

    private JsonNode call(MockHttpServletRequestBuilder builder, Object body) throws Exception {
        String json = perform(builder, body, AS_ENGINEER).getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.isBlank() ? null : objectMapper.readTree(json);
    }

    /** Scenario a deux etapes (une rapide, une lente ou en erreur) sur 2 utilisateurs x 3 iterations. */
    private String scenario(String secondPath) throws Exception {
        String appId = call(post("/api/applications"), Map.of("name", "conservation-" + UUID.randomUUID(), "url", targetUrl)).get("id").asText();
        Map<String, Object> body = new HashMap<>(Map.of("applicationId", appId, "name", "Conservation " + secondPath, "virtualUsers", 2,
                "rampUpSeconds", 0, "thinkTimeMs", 0, "iterations", 3));
        String id = call(post("/api/scenarios"), body).get("id").asText();
        call(post("/api/steps"), Map.of("scenarioId", id, "name", "Accueil", "method", "GET", "url", "/ok", "order", 1));
        call(post("/api/steps"), Map.of("scenarioId", id, "name", "Second appel", "method", "GET", "url", secondPath, "order", 2));
        return id;
    }

    private UUID runToEnd(String scenarioId) throws Exception {
        String executionId = call(post("/api/executions"), Map.of("scenarioId", scenarioId)).get("id").asText();
        UUID id = UUID.fromString(executionId);
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && !snapshotRepository.existsById(id)) {
            Thread.sleep(100);
        }
        assertThat(snapshotRepository.existsById(id)).as("rapport fige enregistre a la fin de l'execution").isTrue();
        return id;
    }

    private void finishedDaysAgo(UUID id, int days) {
        Instant at = Instant.now().minus(Duration.ofDays(days));
        jdbc.update("UPDATE execution SET started_at = ?, finished_at = ? WHERE id = ?",
                Timestamp.from(at.minusSeconds(60)), Timestamp.from(at), id);
    }

    private long count(String table, UUID id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE execution_id = ?", Long.class, id);
    }

    private List<AuditLog> purgeEntries() {
        return auditLogRepository.findAll().stream()
                .filter(a -> a.getAction() == AuditAction.PURGE && a.getModule() == AuditModule.EXECUTION).toList();
    }

    // ------------------------------------------------------------------ tests

    @Test
    void afterRetention_resultsPurged_reportTimelineExtremesAndComparisonUnchanged_withNotice() throws Exception {
        String scenarioId = scenario("/slow");
        UUID old = runToEnd(scenarioId);
        UUID recent = runToEnd(scenarioId);
        assertThat(count("execution_step_result", old)).isEqualTo(12);
        // Plus aucune copie dans metric (moyennes calculees sur les resultats) ; des lignes ANCIENNES peuvent y rester :
        // elles doivent partir avec la purge. 12 lignes « anciennes » recopiees ici des resultats.
        assertThat(count("metric", old)).isZero();
        jdbc.update("INSERT INTO metric (id, execution_id, response_time, status_code, timestamp) "
                + "SELECT RANDOM_UUID(), execution_id, response_time_ms, http_status, captured_at FROM execution_step_result WHERE execution_id = ?", old);
        assertThat(count("metric", old)).isEqualTo(12);

        JsonNode reportBefore = call(get("/api/executions/" + old + "/report"), null);
        JsonNode timelineBefore = call(get("/api/executions/" + old + "/timeline"), null);
        JsonNode slowestBefore = call(get("/api/executions/" + old + "/extremes?kind=MAX"), null);
        JsonNode compareBefore = call(get("/api/executions/compare?ids=" + old + "," + recent), null);
        assertThat(reportBefore.get("retention").get("notice").isNull()).isTrue();
        assertThat(reportBefore.get("retention").get("fromSnapshot").asBoolean()).isFalse();

        finishedDaysAgo(old, 91);
        int auditsBefore = purgeEntries().size();
        ResultsRetentionJob.Outcome outcome = job.purge(Instant.now());

        assertThat(outcome.resultExecutions()).isGreaterThanOrEqualTo(1);
        assertThat(count("execution_step_result", old)).isZero();
        assertThat(count("metric", old)).isZero();
        assertThat(count("execution_request_detail", old)).isZero();
        assertThat(count("execution_step_result", recent)).as("execution recente intacte").isEqualTo(12);

        JsonNode reportAfter = call(get("/api/executions/" + old + "/report"), null);
        assertThat(reportAfter.get("statistics")).isEqualTo(reportBefore.get("statistics"));
        assertThat(reportAfter.get("steps")).isEqualTo(reportBefore.get("steps"));
        assertThat(reportAfter.get("thresholds")).isEqualTo(reportBefore.get("thresholds"));
        assertThat(reportAfter.get("retention").get("fromSnapshot").asBoolean()).isTrue();
        assertThat(reportAfter.get("retention").get("resultsPurgedAt").isNull()).isFalse();
        assertThat(reportAfter.get("retention").get("notice").asText()).isEqualTo("Détail des requêtes supprimé après 90 jours, résumé conservé.");

        JsonNode timelineAfter = call(get("/api/executions/" + old + "/timeline"), null);
        assertThat(timelineAfter.get("p95Ms")).isEqualTo(timelineBefore.get("p95Ms"));
        assertThat(timelineAfter.get("throughput")).isEqualTo(timelineBefore.get("throughput"));
        JsonNode slowestAfter = call(get("/api/executions/" + old + "/extremes?kind=MAX"), null);
        assertThat(slowestAfter.get("cardValueMs")).isEqualTo(slowestBefore.get("cardValueMs"));
        assertThat(slowestAfter.get("requests").size()).isEqualTo(slowestBefore.get("requests").size());
        for (JsonNode r : slowestAfter.get("requests")) assertThat(r.get("request").get("detailKind").isNull()).isTrue();

        JsonNode compareAfter = call(get("/api/executions/compare?ids=" + old + "," + recent), null);
        assertThat(compareAfter.get("columns")).isEqualTo(compareBefore.get("columns"));
        assertThat(compareAfter.get("steps")).isEqualTo(compareBefore.get("steps"));

        // Liste des requetes vide, detail : mention ; liste des executions : date de purge.
        JsonNode detail = call(get("/api/executions/" + old), null);
        assertThat(detail.get("results").size()).isZero();
        assertThat(detail.get("retention").get("notice").asText()).startsWith("Détail des requêtes supprimé");
        JsonNode page = call(get("/api/executions/page?scenarioId=" + scenarioId), null);
        for (JsonNode row : page.get("content")) {
            assertThat(row.get("resultsPurgedAt").isNull()).isEqualTo(!row.get("id").asText().equals(old.toString()));
        }
        // Export CSV du rapport toujours possible.
        assertThat(perform(get("/api/executions/" + old + "/report/export"), null, AS_ENGINEER).getResponse().getStatus()).isEqualTo(200);

        // Une entree « Système » au journal.
        List<AuditLog> entries = purgeEntries();
        assertThat(entries).hasSize(auditsBefore + 1);
        AuditLog entry = entries.get(entries.size() - 1);
        assertThat(entry.getActorType()).isEqualTo("SYSTEM");
        assertThat(entry.getActorSource()).isEqualTo("Purge automatique quotidienne");
        assertThat(entry.getDescription()).contains("de plus de 90 jours supprimés").endsWith("Rapports conservés.");

        // Deuxieme passage : rien a supprimer, aucune nouvelle entree.
        ResultsRetentionJob.Outcome again = job.purge(Instant.now());
        assertThat(again.deletedSomething()).isFalse();
        assertThat(purgeEntries()).hasSize(auditsBefore + 1);
    }

    @Test
    void after30Days_onlyHeadersAndBodiesPurged_resultsKept() throws Exception {
        UUID id = runToEnd(scenario("/fail"));
        assertThat(count("execution_request_detail", id)).as("detail conserve pour les echecs").isPositive();
        String failedResult = jdbc.queryForObject("SELECT CAST(result_id AS VARCHAR) FROM execution_request_detail WHERE execution_id = ? "
                + "FETCH FIRST 1 ROWS ONLY", String.class, id);
        finishedDaysAgo(id, 40);

        job.purge(Instant.now());

        assertThat(count("execution_request_detail", id)).isZero();
        assertThat(count("execution_step_result", id)).isEqualTo(12);
        JsonNode report = call(get("/api/executions/" + id + "/report"), null);
        assertThat(report.get("retention").get("fromSnapshot").asBoolean()).isFalse();
        assertThat(report.get("retention").get("requestDetailsPurgedAt").isNull()).isFalse();
        assertThat(report.get("retention").get("notice").asText())
                .isEqualTo("En-têtes et corps des requêtes supprimés après 30 jours ; la liste des requêtes et le rapport restent complets.");
        MvcResult missing = perform(get("/api/executions/" + id + "/results/" + failedResult + "/detail"), null, AS_ENGINEER);
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(missing.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("En-têtes et corps de cette requête supprimés");
        assertThat(purgeEntries().get(purgeEntries().size() - 1).getDescription()).contains("En-têtes et corps des requêtes de");
    }

    @Test
    void keptAndRunningExecutions_neverPurged_keepRightsAndConflict() throws Exception {
        String scenarioId = scenario("/ok");
        UUID kept = runToEnd(scenarioId);
        UUID running = runToEnd(scenarioId);

        assertThat(perform(patch("/api/executions/" + kept + "/keep"), Map.of("keep", true), AS_VIEWER).getResponse().getStatus()).isEqualTo(403);
        JsonNode keep = call(patch("/api/executions/" + kept + "/keep"), Map.of("keep", true));
        assertThat(keep.get("keep").asBoolean()).isTrue();
        assertThat(auditLogRepository.findAll().stream().anyMatch(a -> a.getAction() == AuditAction.UPDATE
                && kept.toString().equals(a.getTargetId()) && a.getChanges() != null && a.getChanges().contains("Conservés (jamais purgés)"))).isTrue();

        finishedDaysAgo(kept, 400);
        finishedDaysAgo(running, 400);
        jdbc.update("UPDATE execution SET status = 'RUNNING' WHERE id = ?", running);
        try {
            job.purge(Instant.now());
        } finally {
            jdbc.update("UPDATE execution SET status = 'SUCCESS' WHERE id = ?", running);
        }
        assertThat(count("execution_step_result", kept)).isEqualTo(12);
        assertThat(count("execution_step_result", running)).isEqualTo(12);

        // Conservation retiree : purgee au passage suivant ; « Conserver » alors refuse (409).
        call(patch("/api/executions/" + kept + "/keep"), Map.of("keep", false));
        job.purge(Instant.now());
        assertThat(count("execution_step_result", kept)).isZero();
        MvcResult conflict = perform(patch("/api/executions/" + kept + "/keep"), Map.of("keep", true), AS_ENGINEER);
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(conflict.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("seul le résumé est conservé");
    }

    @Test
    void nothingToPurge_noAuditEntry() {
        int before = purgeEntries().size();
        ResultsRetentionJob.Outcome outcome = job.purge(Instant.parse("2000-01-01T00:00:00Z"));
        assertThat(outcome.deletedSomething()).isFalse();
        assertThat(purgeEntries()).hasSize(before);
    }

    @Test
    void backfill_recreatesMissingSnapshot() throws Exception {
        UUID id = runToEnd(scenario("/ok"));
        snapshotRepository.deleteById(id);
        assertThat(snapshotService.backfill()).isGreaterThanOrEqualTo(1);
        assertThat(snapshotRepository.existsById(id)).isTrue();
    }

    @Test
    void settings_defaults_validation_audit_andAdminOnly() throws Exception {
        JsonNode defaults = objectMapper.readTree(perform(get("/api/settings/results-retention"), null, AS_ADMIN).getResponse().getContentAsString());
        assertThat(defaults.get("resultsDays").asInt()).isEqualTo(90);
        assertThat(defaults.get("requestDetailsDays").asInt()).isEqualTo(30);

        assertThat(perform(get("/api/settings/results-retention"), null, AS_ENGINEER).getResponse().getStatus()).isEqualTo(403);
        assertThat(perform(put("/api/settings/results-retention"), Map.of("resultsDays", 60, "requestDetailsDays", 20), AS_ENGINEER)
                .getResponse().getStatus()).isEqualTo(403);

        MvcResult longer = perform(put("/api/settings/results-retention"), Map.of("resultsDays", 60, "requestDetailsDays", 90), AS_ADMIN);
        assertThat(longer.getResponse().getStatus()).isEqualTo(400);
        assertThat(longer.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("plus longtemps que les résultats");
        assertThat(perform(put("/api/settings/results-retention"), Map.of("resultsDays", 3, "requestDetailsDays", 1), AS_ADMIN)
                .getResponse().getStatus()).isEqualTo(400);

        JsonNode updated = objectMapper.readTree(perform(put("/api/settings/results-retention"),
                Map.of("resultsDays", 60, "requestDetailsDays", 20), AS_ADMIN).getResponse().getContentAsString());
        assertThat(updated.get("resultsDays").asInt()).isEqualTo(60);
        assertThat(settings.resultsRetentionDays()).isEqualTo(60);
        assertThat(auditLogRepository.findAll().stream().anyMatch(a -> a.getAction() == AuditAction.UPDATE
                && "results-retention".equals(a.getTargetId()) && a.getChanges() != null
                && a.getChanges().contains("90 jours") && a.getChanges().contains("60 jours"))).isTrue();
    }
}
