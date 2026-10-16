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
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
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
 * Exports d'un rapport : Excel (.xlsx, quatre feuilles typees, en-tetes figes, filtres) et CSV
 * « Excel en francais » (un tableau par fichier, BOM, « ; », virgule decimale, dates jj/mm/aaaa),
 * archive .zip des quatre tableaux, rapport fige, aucun secret, droits, journal.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportExportTest {

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
    private static final Pattern DATE_TIME = Pattern.compile("\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}:\\d{2}");

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

    /** Deux etapes : « Accueil » (toujours 200) et « Paiement ; carte "Visa" » (secondUrl), 2 utilisateurs x 3 iterations. */
    private UUID run(String name, String secondUrl) throws Exception {
        String appId = call(post("/api/applications"), Map.of("name", "export-" + UUID.randomUUID(), "url", targetUrl)).get("id").asText();
        Map<String, Object> body = new HashMap<>(Map.of("applicationId", appId, "name", name, "virtualUsers", 2,
                "rampUpSeconds", 0, "thinkTimeMs", 0, "iterations", 3));
        String scenarioId = call(post("/api/scenarios"), body).get("id").asText();
        call(post("/api/steps"), Map.of("scenarioId", scenarioId, "name", "Accueil", "method", "GET", "url", "/ok", "order", 1));
        call(post("/api/steps"), Map.of("scenarioId", scenarioId, "name", "Paiement ; carte \"Visa\"", "method", "GET", "url", secondUrl, "order", 2));
        UUID id = UUID.fromString(call(post("/api/executions"), Map.of("scenarioId", scenarioId)).get("id").asText());
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && !snapshotRepository.existsById(id)) Thread.sleep(100);
        assertThat(snapshotRepository.existsById(id)).as("execution terminee").isTrue();
        return id;
    }

    private static Map<String, String> unzip(byte[] bytes) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                files.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    /** Lignes d'une feuille : chaque cellule « reference -> [style, type, valeur] » (texte en ligne decode). */
    private static List<Map<String, String[]>> rows(String sheetXml) {
        List<Map<String, String[]>> rows = new java.util.ArrayList<>();
        Matcher row = Pattern.compile("<row r=\"(\\d+)\"[^>]*>(.*?)</row>").matcher(sheetXml);
        while (row.find()) {
            Map<String, String[]> cells = new LinkedHashMap<>();
            Matcher c = Pattern.compile("<c r=\"([A-Z]+)\\d+\" s=\"(\\d+)\"(?: t=\"(\\w+)\")?>(?:<v>([^<]*)</v>|<is><t[^>]*>([^<]*)</t></is>)</c>").matcher(row.group(2));
            while (c.find()) {
                String value = c.group(4) != null ? c.group(4) : c.group(5).replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
                cells.put(c.group(1), new String[] {c.group(2), c.group(3), value});
            }
            rows.add(cells);
        }
        return rows;
    }

    private List<AuditLog> audits(UUID id, AuditAction action) {
        return auditLogRepository.findAll().stream().filter(a -> a.getAction() == action && id.toString().equals(a.getTargetId())).toList();
    }

    // ------------------------------------------------------------------ Excel

    @Test
    void xlsx_fourTypedSheets_frozenBoldHeaders_filters_viewerAllowed_audited() throws Exception {
        UUID id = run("Export Excel", "/fail");
        MockHttpServletResponse response = perform(get("/api/executions/" + id + "/report/xlsx"), null, AS_VIEWER);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(response.getHeader("Content-Disposition"))
                .matches("attachment; filename=\"Rapport_Export-Excel_\\d{4}-\\d{2}-\\d{2}_\\d{2}h\\d{2}\\.xlsx\"");

        Map<String, String> files = unzip(response.getContentAsByteArray());
        assertThat(files).containsKeys("[Content_Types].xml", "xl/workbook.xml", "xl/styles.xml", "xl/worksheets/sheet1.xml",
                "xl/worksheets/sheet4.xml");
        assertThat(files.get("xl/workbook.xml")).contains("name=\"Résumé\"", "name=\"Étapes\"", "name=\"Erreurs\"", "name=\"Analyse dans le temps\"");
        assertThat(files.get("xl/styles.xml")).contains("<b/>", "formatCode=\"dd/mm/yyyy\\ hh:mm:ss\"");

        for (int i = 1; i <= 4; i++) {
            String sheet = files.get("xl/worksheets/sheet" + i + ".xml");
            assertThat(sheet).as("feuille %d : en-tete fige, filtre, largeurs", i)
                    .contains("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
                    .contains("<autoFilter ref=\"A1:").contains("customWidth=\"1\"");
            rows(sheet).get(0).values().forEach(cell -> assertThat(cell[0]).as("en-tete en gras (style 1)").isEqualTo("1"));
        }

        // Resume : libelles en francais avec unites, nombres stockes comme nombres, dates Excel.
        Map<String, String[]> summary = new LinkedHashMap<>();
        for (Map<String, String[]> r : rows(files.get("xl/worksheets/sheet1.xml"))) {
            if (r.containsKey("A") && r.containsKey("B")) summary.put(r.get("A")[2], r.get("B"));
        }
        assertThat(summary.get("Scénario")[2]).isEqualTo("Export Excel");
        assertThat(summary.get("Résultat")[2]).isEqualTo("Seuils dépassés");
        assertThat(summary.get("Requêtes")).satisfies(c -> { assertThat(c[1]).isNull(); assertThat(c[2]).isEqualTo("12"); });
        assertThat(summary.get("Taux d’erreurs (%)")).satisfies(c -> { assertThat(c[1]).isNull(); assertThat(c[2]).isEqualTo("50"); });
        assertThat(summary.get("Lancé le")).satisfies(c -> { assertThat(c[0]).isEqualTo("5"); assertThat(Double.parseDouble(c[2])).isGreaterThan(46000); });
        assertThat(summary).containsKeys("Débit moyen (req/s)", "P95 (ms)", "P99 (ms)", "Temps min (ms)", "Temps moyen (ms)", "Temps max (ms)",
                "Seuil : taux d’erreurs maximal (%)", "Utilisateurs virtuels", "Ramp-up (s)", "CPU max de l’application (% d’un cœur)");

        // Etapes : une ligne par etape, colonnes numeriques en nombres.
        List<Map<String, String[]>> steps = rows(files.get("xl/worksheets/sheet2.xml"));
        assertThat(steps.get(0).values().stream().map(c -> c[2]).toList()).containsExactly("Étape", "Méthode", "URL", "Requêtes", "Succès",
                "Échecs", "Taux d’erreurs (%)", "Min (ms)", "Moyenne (ms)", "P95 (ms)", "P99 (ms)", "Max (ms)");
        assertThat(steps).hasSize(3);
        Map<String, String[]> payment = steps.stream().filter(r -> r.get("A")[2].startsWith("Paiement")).findFirst().orElseThrow();
        assertThat(payment.get("A")[2]).isEqualTo("Paiement ; carte \"Visa\"");
        assertThat(payment.get("D")[2]).isEqualTo("6");
        assertThat(payment.get("F")[2]).isEqualTo("6");
        assertThat(payment.get("G")[2]).isEqualTo("100");
        assertThat(payment.get("D")[1]).as("nombre, pas texte").isNull();

        // Erreurs : regroupees (code, etape, message), nombre et premiere apparition (date).
        List<Map<String, String[]>> errors = rows(files.get("xl/worksheets/sheet3.xml"));
        assertThat(errors.get(0).values().stream().map(c -> c[2]).toList())
                .containsExactly("Code HTTP", "Étape", "Message", "Nombre", "Première apparition");
        assertThat(errors).hasSize(2);
        assertThat(errors.get(1).get("A")[2]).isEqualTo("500");
        assertThat(errors.get(1).get("D")[2]).isEqualTo("6");
        assertThat(errors.get(1).get("E")[0]).isEqualTo("5");

        // Analyse dans le temps : une ligne par periode.
        List<Map<String, String[]>> timeline = rows(files.get("xl/worksheets/sheet4.xml"));
        assertThat(timeline.get(0).values().stream().map(c -> c[2]).toList()).containsExactly("Temps depuis le début (s)", "Heure", "VU actifs",
                "Requêtes", "Débit (req/s)", "Temps moyen (ms)", "P95 (ms)", "Erreurs", "CPU (% d’un cœur)", "RAM (Mo)");
        assertThat(timeline.size()).isGreaterThan(1);
        assertThat(timeline.get(1).get("B")[0]).isEqualTo("5");

        assertThat(audits(id, AuditAction.EXPORT_REPORT)).singleElement().satisfies(a -> {
            assertThat(a.getDescription()).isEqualTo("Rapport exporté au format Excel");
            assertThat(a.getUsername()).isEqualTo("viewer-test");
        });
    }

    // ------------------------------------------------------------------ CSV

    @Test
    void csv_oneTablePerFile_frenchExcelFormat_quoting_tables_zip_audited() throws Exception {
        UUID id = run("Export CSV", "/fail");
        MockHttpServletResponse response = perform(get("/api/executions/" + id + "/report/export"), null, AS_VIEWER);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("text/csv; charset=UTF-8");
        assertThat(response.getHeader("Content-Disposition"))
                .matches("attachment; filename=\"Rapport_Export-CSV_\\d{4}-\\d{2}-\\d{2}_\\d{2}h\\d{2}_Etapes\\.csv\"");
        byte[] bytes = response.getContentAsByteArray();
        assertThat(new byte[] {bytes[0], bytes[1], bytes[2]}).containsExactly(0xEF, 0xBB, 0xBF);
        String[] lines = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8).split("\r\n");
        assertThat(lines[0]).isEqualTo("Étape;Méthode;URL;Requêtes;Succès;Échecs;Taux d’erreurs (%);Min (ms);Moyenne (ms);P95 (ms);P99 (ms);Max (ms)");
        assertThat(lines).hasSize(3);
        // Valeur contenant « ; » et des guillemets : entre guillemets, guillemet interne double ; virgule decimale.
        assertThat(lines[2]).startsWith("\"Paiement ; carte \"\"Visa\"\"\";GET;/fail;6;0;6;100,00;");
        assertThat(lines[1]).matches("Accueil;GET;/ok;6;6;0;0,00;\\d+;\\d+;\\d+;\\d+;\\d+");

        String errors = new String(perform(get("/api/executions/" + id + "/report/export").param("table", "errors"), null, AS_VIEWER)
                .getContentAsByteArray(), StandardCharsets.UTF_8).substring(1);
        String[] errorLines = errors.split("\r\n");
        assertThat(errorLines[0]).isEqualTo("Code HTTP;Étape;Message;Nombre;Première apparition");
        assertThat(errorLines[1]).startsWith("500;\"Paiement ; carte \"\"Visa\"\"\";");
        assertThat(errorLines[1]).contains(";6;");
        assertThat(DATE_TIME.matcher(errorLines[1]).find()).as("date jj/mm/aaaa hh:mm:ss").isTrue();

        String timeline = new String(perform(get("/api/executions/" + id + "/report/export").param("table", "timeline"), null, AS_VIEWER)
                .getContentAsByteArray(), StandardCharsets.UTF_8).substring(1);
        assertThat(timeline.split("\r\n")[0]).isEqualTo("Temps depuis le début (s);Heure;VU actifs;Requêtes;Débit (req/s);Temps moyen (ms);P95 (ms);Erreurs;CPU (% d’un cœur);RAM (Mo)");
        assertThat(timeline.split("\r\n")[1]).matches("0;\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}:\\d{2};.*");

        String summary = new String(perform(get("/api/executions/" + id + "/report/export").param("table", "summary"), null, AS_VIEWER)
                .getContentAsByteArray(), StandardCharsets.UTF_8).substring(1);
        assertThat(summary).startsWith("Indicateur;Valeur\r\n").contains("Résultat;Seuils dépassés\r\n", "Requêtes;12\r\n", "Taux d’erreurs (%);50,00\r\n");
        assertThat(DATE_TIME.matcher(summary).find()).isTrue();

        assertThat(perform(get("/api/executions/" + id + "/report/export").param("table", "inconnu"), null, AS_VIEWER).getStatus()).isEqualTo(400);

        MockHttpServletResponse zip = perform(get("/api/executions/" + id + "/report/export.zip"), null, AS_VIEWER);
        assertThat(zip.getStatus()).isEqualTo(200);
        assertThat(zip.getHeader("Content-Disposition")).matches("attachment; filename=\"Rapport_Export-CSV_.*_CSV\\.zip\"");
        Map<String, String> files = unzip(zip.getContentAsByteArray());
        assertThat(files.keySet()).hasSize(4).allMatch(n -> n.startsWith("Rapport_Export-CSV_") && n.endsWith(".csv"));
        assertThat(files.keySet().stream().map(n -> n.substring(n.lastIndexOf('_') + 1)).toList())
                .containsExactly("Resume.csv", "Etapes.csv", "Erreurs.csv", "Analyse-dans-le-temps.csv");
        assertThat(files.values()).allMatch(content -> content.startsWith("﻿"));

        List<AuditLog> csvAudits = audits(id, AuditAction.EXPORT_CSV);
        assertThat(csvAudits).extracting(AuditLog::getDescription).contains("Rapport exporté au format CSV (étapes)",
                "Rapport exporté au format CSV (erreurs)", "Rapport exporté au format CSV (archive .zip des 4 tableaux)");
        assertThat(csvAudits).filteredOn(a -> a.getResult() == AuditResult.FAILURE).singleElement()
                .satisfies(a -> assertThat(a.getDescription()).startsWith("Export CSV du rapport impossible"));
    }

    // ------------------------------------------------------------------ cas particuliers

    @Test
    void exports_frozenReport_secretsMasked_unfinishedRefused() throws Exception {
        UUID id = run("Rapport figé export", "/ok?token=ABC123SECRETXYZ");
        Instant at = Instant.now().minus(Duration.ofDays(91));
        jdbc.update("UPDATE execution SET started_at = ?, finished_at = ? WHERE id = ?", Timestamp.from(at.minusSeconds(60)), Timestamp.from(at), id);
        retentionJob.purge(Instant.now());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM execution_step_result WHERE execution_id = ?", Long.class, id)).isZero();

        Map<String, String> xlsx = unzip(perform(get("/api/executions/" + id + "/report/xlsx"), null, AS_VIEWER).getContentAsByteArray());
        String all = String.join("\n", xlsx.values());
        assertThat(all).contains("Rapport figé (détail des requêtes supprimé le)", "Réussie", "Accueil").doesNotContain("ABC123SECRETXYZ");
        assertThat(rows(xlsx.get("xl/worksheets/sheet2.xml"))).hasSize(3);

        String steps = new String(perform(get("/api/executions/" + id + "/report/export"), null, AS_VIEWER).getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(steps).contains("Accueil;GET;/ok;6;6;0;0,00;").doesNotContain("ABC123SECRETXYZ");

        jdbc.update("UPDATE execution SET status = 'RUNNING' WHERE id = ?", id);
        try {
            for (String path : List.of("/report/xlsx", "/report/export", "/report/export.zip")) {
                MockHttpServletResponse refused = perform(get("/api/executions/" + id + path), null, AS_ENGINEER);
                assertThat(refused.getStatus()).as(path).isEqualTo(409);
                assertThat(objectMapper.readTree(refused.getContentAsString(StandardCharsets.UTF_8)).get("message").asText())
                        .isEqualTo("Le rapport est exportable une fois l’exécution terminée.");
            }
        } finally {
            jdbc.update("UPDATE execution SET status = 'SUCCESS' WHERE id = ?", id);
        }
        assertThat(perform(get("/api/executions/" + UUID.randomUUID() + "/report/xlsx"), null, AS_VIEWER).getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(get("/api/executions/" + id + "/report/xlsx")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }
}
