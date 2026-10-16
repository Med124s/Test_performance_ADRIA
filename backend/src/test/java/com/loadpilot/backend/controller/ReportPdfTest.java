package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.entity.AuditLog;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.repository.AuditLogRepository;
import com.loadpilot.backend.repository.ExecutionReportSnapshotRepository;
import com.loadpilot.backend.service.impl.ResultsRetentionJob;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Page Rapports : export PDF d'un rapport (contenu en francais, aucun secret, rapport fige apres
 * purge, refus pour une execution non terminee, droits, journal) et liste des rapports (seulement
 * les executions terminees, filtres combines, pagination, tri par date).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportPdfTest {

    private static HttpServer server;
    private static String targetUrl;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ResultsRetentionJob retentionJob;
    @Autowired private ExecutionReportSnapshotRepository snapshotRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @MockBean private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_ENGINEER = jwt().jwt(j -> j.claim("preferred_username", "engineer-test"))
            .authorities(new SimpleGrantedAuthority("ROLE_PERFORMANCE_ENGINEER"));
    private static final RequestPostProcessor AS_VIEWER = jwt().jwt(j -> j.claim("preferred_username", "viewer-test"))
            .authorities(new SimpleGrantedAuthority("ROLE_VIEWER"));

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", ex -> {
            try {
                Thread.sleep(3);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            boolean fail = ex.getRequestURI().getPath().equals("/fail");
            byte[] bytes = (fail ? "{\"error\":\"panne\"}" : "{\"ok\":true}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(fail ? 500 : 200, bytes.length);
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

    // ------------------------------------------------------------------ outils

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder builder, Object body, RequestPostProcessor as) throws Exception {
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        return mockMvc.perform(builder.with(as)).andReturn().getResponse();
    }

    private JsonNode call(MockHttpServletRequestBuilder builder, Object body) throws Exception {
        String json = perform(builder, body, AS_ENGINEER).getContentAsString(StandardCharsets.UTF_8);
        return json.isBlank() ? null : objectMapper.readTree(json);
    }

    private String application(String name) throws Exception {
        return call(post("/api/applications"), Map.of("name", name + "-" + UUID.randomUUID(), "url", targetUrl)).get("id").asText();
    }

    /** Deux etapes : « Accueil » (toujours 200) et une seconde (« /fail » : 500), 2 utilisateurs x 3 iterations. */
    private String scenario(String appId, String name, String secondUrl) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("applicationId", appId, "name", name, "virtualUsers", 2,
                "rampUpSeconds", 0, "thinkTimeMs", 0, "iterations", 3));
        String id = call(post("/api/scenarios"), body).get("id").asText();
        call(post("/api/steps"), Map.of("scenarioId", id, "name", "Accueil", "method", "GET", "url", "/ok", "order", 1));
        call(post("/api/steps"), Map.of("scenarioId", id, "name", "Paiement", "method", "GET", "url", secondUrl, "order", 2));
        return id;
    }

    private UUID runToEnd(String scenarioId) throws Exception {
        UUID id = UUID.fromString(call(post("/api/executions"), Map.of("scenarioId", scenarioId)).get("id").asText());
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && !snapshotRepository.existsById(id)) Thread.sleep(100);
        assertThat(snapshotRepository.existsById(id)).as("execution terminee").isTrue();
        return id;
    }

    private static String text(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        try {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder out = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) out.append(extractor.getTextFromPage(page)).append('\n');
            return out.toString().replace(' ', ' ');
        } finally {
            reader.close();
        }
    }

    private List<AuditLog> pdfAudits(UUID id) {
        return auditLogRepository.findAll().stream()
                .filter(a -> a.getAction() == AuditAction.EXPORT_REPORT && id.toString().equals(a.getTargetId())).toList();
    }

    // ------------------------------------------------------------------ tests

    @Test
    void pdf_frenchReport_allSections_viewerAllowed_audited() throws Exception {
        String app = application("pdf");
        UUID id = runToEnd(scenario(app, "Parcours PDF", "/fail"));

        MockHttpServletResponse response = perform(get("/api/executions/" + id + "/report/pdf"), null, AS_VIEWER);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("application/pdf");
        assertThat(response.getHeader("Content-Disposition")).matches("attachment; filename=\"Rapport_Parcours-PDF_\\d{4}-\\d{2}-\\d{2}_\\d{2}h\\d{2}\\.pdf\"");
        byte[] pdf = response.getContentAsByteArray();
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");

        String text = text(pdf);
        assertThat(text).contains("LoadPilot", "Rapport de test de performance", "Parcours PDF", "Application : pdf-")
                .contains("Résultat", "Seuils dépassés", "Seuil de réussite", "Taux d’erreurs")
                .contains("Chiffres clés", "Requêtes", "12", "P95", "P99", "Débit moyen")
                .contains("Analyse dans le temps", "Détail par étape", "Accueil", "Paiement", "GET /fail")
                .contains("Erreurs", "500")
                .contains("CPU et mémoire de l’application testée", "Aucune mesure")
                .contains("Conclusion", "Erreur la plus fréquente", "Utilisateurs virtuels", "Itérations", "3 par utilisateur")
                .containsPattern("Page 1 sur \\d");
        // Jamais de caractere remplace par « ? » faute de police.
        assertThat(text).doesNotContain("?");

        List<AuditLog> audits = pdfAudits(id);
        assertThat(audits).singleElement().satisfies(a -> {
            assertThat(a.getDescription()).isEqualTo("Rapport exporté au format PDF");
            assertThat(a.getUsername()).isEqualTo("viewer-test");
            assertThat(a.getResult()).isEqualTo(AuditResult.SUCCESS);
        });
    }

    @Test
    void pdf_neverContainsSecrets() throws Exception {
        String app = application("secret");
        UUID id = runToEnd(scenario(app, "Secret", "/fail?token=ABC123SECRETXYZ&page=2"));
        String text = text(perform(get("/api/executions/" + id + "/report/pdf"), null, AS_ENGINEER).getContentAsByteArray());
        assertThat(text).contains("Secret", "Paiement").doesNotContain("ABC123SECRETXYZ");
    }

    @Test
    void pdf_frozenReportAfterPurge_stillComplete_withNotice() throws Exception {
        String app = application("fige");
        UUID id = runToEnd(scenario(app, "Rapport figé", "/ok"));
        Instant at = Instant.now().minus(Duration.ofDays(91));
        jdbc.update("UPDATE execution SET started_at = ?, finished_at = ? WHERE id = ?", Timestamp.from(at.minusSeconds(60)), Timestamp.from(at), id);
        retentionJob.purge(Instant.now());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM execution_step_result WHERE execution_id = ?", Long.class, id)).isZero();

        MockHttpServletResponse response = perform(get("/api/executions/" + id + "/report/pdf"), null, AS_VIEWER);
        assertThat(response.getStatus()).isEqualTo(200);
        String text = text(response.getContentAsByteArray());
        assertThat(text).contains("Rapport figé : le détail des requêtes a été supprimé le", "Réussie", "12", "Accueil", "Paiement",
                "Aucune erreur", "Conclusion", "Test réussi");
    }

    @Test
    void pdf_refusedForUnfinishedExecution_404Unknown_401Anonymous() throws Exception {
        String app = application("actif");
        UUID id = runToEnd(scenario(app, "Bientôt actif", "/ok"));
        jdbc.update("UPDATE execution SET status = 'RUNNING' WHERE id = ?", id);
        try {
            MockHttpServletResponse running = perform(get("/api/executions/" + id + "/report/pdf"), null, AS_ENGINEER);
            assertThat(running.getStatus()).isEqualTo(409);
            assertThat(objectMapper.readTree(running.getContentAsString(StandardCharsets.UTF_8)).get("message").asText())
                    .isEqualTo("Le rapport PDF est disponible une fois l’exécution terminée.");
            assertThat(pdfAudits(id)).singleElement().satisfies(a -> assertThat(a.getResult()).isEqualTo(AuditResult.FAILURE));
        } finally {
            jdbc.update("UPDATE execution SET status = 'SUCCESS' WHERE id = ?", id);
        }
        assertThat(perform(get("/api/executions/" + UUID.randomUUID() + "/report/pdf"), null, AS_VIEWER).getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(get("/api/executions/" + id + "/report/pdf")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void reportsList_finishedOnly_filtersCombined_paginated_newestFirst() throws Exception {
        String app = application("liste");
        String okScenario = scenario(app, "Liste réussie", "/ok");
        String koScenario = scenario(app, "Liste en erreur", "/fail");
        UUID first = runToEnd(okScenario);
        UUID second = runToEnd(okScenario);
        UUID failing = runToEnd(koScenario);
        // La plus ancienne : il y a 40 jours (hors « 30 jours »).
        Instant old = Instant.now().minus(Duration.ofDays(40));
        jdbc.update("UPDATE execution SET started_at = ?, finished_at = ? WHERE id = ?", Timestamp.from(old), Timestamp.from(old.plusSeconds(5)), first);

        JsonNode all = objectMapper.readTree(perform(get("/api/executions/page").param("finished", "true").param("applicationId", app)
                .param("size", "10"), null, AS_VIEWER).getContentAsString(StandardCharsets.UTF_8));
        assertThat(all.get("content").findValuesAsText("id")).containsExactly(failing.toString(), second.toString(), first.toString());
        assertThat(all.get("counts").get("thresholdsExceeded").asLong()).isEqualTo(1);
        assertThat(all.get("counts").get("success").asLong()).isEqualTo(2);

        JsonNode byResult = call(get("/api/executions/page").param("finished", "true").param("applicationId", app)
                .param("status", "THRESHOLDS_EXCEEDED"), null);
        assertThat(byResult.get("content").findValuesAsText("id")).containsExactly(failing.toString());
        assertThat(byResult.get("content").get(0).get("statusReason").asText()).contains("Erreurs");

        JsonNode byScenarioAndPeriod = call(get("/api/executions/page").param("finished", "true").param("scenarioId", okScenario)
                .param("dateFrom", Instant.now().minus(Duration.ofDays(30)).toString()), null);
        assertThat(byScenarioAndPeriod.get("content").findValuesAsText("id")).containsExactly(second.toString());

        JsonNode search = call(get("/api/executions/page").param("finished", "true").param("search", "en erreur"), null);
        assertThat(search.get("content").findValuesAsText("id")).contains(failing.toString()).doesNotContain(first.toString());

        // Pagination serveur : 5 par page au minimum, page suivante vide ici, total exact.
        JsonNode page = call(get("/api/executions/page").param("finished", "true").param("applicationId", app).param("size", "5").param("page", "1"), null);
        assertThat(page.get("content").size()).isZero();
        assertThat(page.get("totalElements").asLong()).isEqualTo(3);
        assertThat(page.get("size").asInt()).isEqualTo(5);
    }
}
