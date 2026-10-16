package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.service.impl.DashboardRules;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
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
 * Nouveau Dashboard (GET /api/dashboard/overview et /scenario-trend) sur de vraies executions :
 * filtres application et periode, P95 de la periode identique a celui du rapport, comparaison
 * avec l'execution precedente, regression, points a surveiller, etats vides.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardOverviewTest {

    private static HttpServer server;
    private static String targetUrl;
    private static volatile int latencyMs = 10;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ExecutionRepository executionRepository;
    @MockBean
    private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_ADMIN = jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            try {
                Thread.sleep(latencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            boolean ko = path.startsWith("/ko");
            byte[] bytes = (ko ? "{\"success\":false}" : "{\"success\":true}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(ko ? 500 : 200, bytes.length);
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

    private MvcResult perform(MockHttpServletRequestBuilder builder, Object body) throws Exception {
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        return mockMvc.perform(builder.with(AS_ADMIN)).andReturn();
    }

    private JsonNode json(MockHttpServletRequestBuilder builder, Object body) throws Exception {
        String s = perform(builder, body).getResponse().getContentAsString(StandardCharsets.UTF_8);
        return s.isBlank() ? null : objectMapper.readTree(s);
    }

    private String app(String name, Map<String, Object> monitoring) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("name", name + "-" + UUID.randomUUID(), "url", targetUrl));
        if (monitoring != null) body.put("monitoring", monitoring);
        return json(post("/api/applications"), body).get("id").asText();
    }

    private String scenario(String appId, String name, String path) throws Exception {
        String id = json(post("/api/scenarios"), Map.of("applicationId", appId, "name", name, "virtualUsers", 1, "rampUpSeconds", 0,
                "iterations", 20, "thinkTimeMs", 0)).get("id").asText();
        json(post("/api/steps"), Map.of("scenarioId", id, "name", "Appel " + name, "method", "GET", "url", path, "order", 1,
                "expectedStatus", 200, "assertionBodyContains", "\"success\":true"));
        return id;
    }

    /** Lance et attend la fin (y compris l'enregistrement) ; renvoie l'id de l'execution. */
    private String run(String scenarioId) throws Exception {
        String id = json(post("/api/executions"), Map.of("scenarioId", scenarioId)).get("id").asText();
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(150);
            String status = json(get("/api/executions/" + id + "/status"), null).get("status").asText();
            if (List.of("SUCCESS", "THRESHOLDS_EXCEEDED", "FAILED").contains(status)
                    && executionRepository.findById(UUID.fromString(id)).map(e -> e.getStatsComputedAt() != null).orElse(false)) {
                break;
            }
        }
        return id;
    }

    private JsonNode overview(String appId, Instant from, Instant to) throws Exception {
        MockHttpServletRequestBuilder req = get("/api/dashboard/overview");
        if (appId != null) req.param("applicationId", appId);
        if (from != null) req.param("from", from.toString());
        if (to != null) req.param("to", to.toString());
        return json(req, null);
    }

    /** Tableau « Derniere execution par scenario » (pagine). */
    private JsonNode latest(String appId, Instant from, Instant to, Integer page, Integer size) throws Exception {
        MockHttpServletRequestBuilder req = get("/api/dashboard/latest-by-scenario");
        if (appId != null) req.param("applicationId", appId);
        if (from != null) req.param("from", from.toString());
        if (to != null) req.param("to", to.toString());
        if (page != null) req.param("page", String.valueOf(page));
        if (size != null) req.param("size", String.valueOf(size));
        return json(req, null);
    }

    private static List<String> kinds(JsonNode overview) {
        List<String> out = new ArrayList<>();
        overview.get("watchItems").forEach(i -> out.add(i.get("kind").asText() + ":" + i.get("title").asText()));
        return out;
    }

    @Test
    void filters_p95_comparison_regression_and_watchItems_onRealExecutions() throws Exception {
        Instant testStart = Instant.now().minusSeconds(1);
        // Echauffement (client HTTP et JIT) : la premiere execution de la JVM ne fausse pas la comparaison.
        run(scenario(app("dash-warmup", null), "Echauffement-" + UUID.randomUUID().toString().substring(0, 6), "/ok"));
        String appA = app("dash-a", null);
        String s1 = scenario(appA, "Lent-" + UUID.randomUUID().toString().substring(0, 6), "/ok");
        latencyMs = 10;
        String run1 = run(s1);
        latencyMs = 120; // le meme scenario devient nettement plus lent : regression
        String run2 = run(s1);
        Instant afterRun1 = Instant.parse(json(get("/api/executions/" + run2 + "/report"), null).get("startedAt").asText());

        String appB = app("dash-b", null);
        latencyMs = 10;
        String s2 = scenario(appB, "Echec-" + UUID.randomUUID().toString().substring(0, 6), "/ko");
        String run3 = run(s2);

        // ---- application A seule : 2 executions, comparaison run2 / run1, regression
        JsonNode a = overview(appA, testStart, null);
        assertThat(a.get("kpis").get("executions").asLong()).isEqualTo(2);
        assertThat(a.get("kpis").get("requests").asLong()).isEqualTo(40);
        assertThat(a.get("kpis").get("errorRate").asDouble()).isZero();
        assertThat(a.get("statusCounts").get("success").asLong()).isEqualTo(2);
        JsonNode rowsA = latest(appA, testStart, null, null, null);
        assertThat(rowsA.get("totalElements").asLong()).isEqualTo(1);
        assertThat(rowsA.get("size").asInt()).as("10 lignes par page par defaut").isEqualTo(10);
        JsonNode row = rowsA.get("content").get(0);
        assertThat(a.get("scenarios")).hasSize(1);
        assertThat(a.get("scenarios").get(0).get("scenarioId").asText()).isEqualTo(s1);
        assertThat(a.has("latestByScenario")).as("plus de liste complete dans la vue d'ensemble").isFalse();
        assertThat(row.get("executionId").asText()).isEqualTo(run2);
        assertThat(row.get("previousExecutionId").asText()).isEqualTo(run1);
        JsonNode report1 = json(get("/api/executions/" + run1 + "/report"), null).get("statistics");
        JsonNode report2 = json(get("/api/executions/" + run2 + "/report"), null).get("statistics");
        assertThat(row.get("p95Ms").asLong()).as("P95 identique au rapport").isEqualTo(report2.get("p95").asLong());
        assertThat(row.get("previousP95Ms").asLong()).isEqualTo(report1.get("p95").asLong());
        assertThat(row.get("throughputRps").asDouble()).isEqualTo(report2.get("throughput").asDouble());
        assertThat(row.get("p95ChangePercent").asDouble()).isGreaterThan(DashboardRules.REGRESSION_PERCENT);
        assertThat(row.get("trend").asText()).isEqualTo("WORSE");
        assertThat(kinds(a)).anyMatch(k -> k.startsWith("REGRESSION:Scénario « "));

        // ---- periode commencant a run2 : P95 de la periode = P95 du rapport de run2
        JsonNode onlyRun2 = overview(appA, afterRun1, null);
        assertThat(onlyRun2.get("kpis").get("executions").asLong()).isEqualTo(1);
        assertThat(onlyRun2.get("kpis").get("p95Ms").asLong()).isEqualTo(report2.get("p95").asLong());
        assertThat(latest(appA, afterRun1, null, null, null).get("content").get(0).get("previousExecutionId").asText())
                .as("comparaison avec l'execution precedente meme hors periode").isEqualTo(run1);

        // ---- application B : derniere execution allee au bout avec 100 % d'erreurs -> seuils depasses, aucune regression
        JsonNode b = overview(appB, testStart, null);
        assertThat(b.get("kpis").get("errorRate").asDouble()).isEqualTo(100.0);
        assertThat(b.get("statusCounts").get("thresholdsExceeded").asLong()).isEqualTo(1);
        assertThat(b.get("statusCounts").get("failed").asLong()).isZero();
        assertThat(latest(appB, testStart, null, null, null).get("content").get(0).get("trend").asText()).isEqualTo("NONE");
        assertThat(kinds(b)).hasSize(1).allMatch(k -> k.startsWith("THRESHOLDS_EXCEEDED:"));
        assertThat(b.get("watchItems").get(0).get("detail").asText()).isEqualTo("Taux d'erreurs 100 %");
        assertThat(b.get("watchItems").get(0).get("link").asText()).isEqualTo("/executions/report/" + run3);

        // ---- toutes les applications : les deux scenarios, tries du plus recent au plus ancien
        JsonNode all = overview(null, testStart, null);
        List<String> rows = new ArrayList<>();
        latest(null, testStart, null, 0, 100).get("content").forEach(r -> rows.add(r.get("executionId").asText()));
        assertThat(rows).containsSubsequence(run3, run2);
        assertThat(all.get("recent").get(0).get("id").asText()).isEqualTo(run3);
        assertThat(all.get("slowestSteps").get(0).get("p95Ms").asLong()).isEqualTo(report2.get("p95").asLong());

        // ---- evolution du scenario 1 : deux points, ordre chronologique, valeurs des rapports
        JsonNode trend = json(get("/api/dashboard/scenario-trend").param("scenarioId", s1).param("from", testStart.toString()), null);
        assertThat(trend.get("points").size()).isEqualTo(2);
        assertThat(trend.get("points").get(0).get("executionId").asText()).isEqualTo(run1);
        assertThat(trend.get("points").get(1).get("p95Ms").asLong()).isEqualTo(report2.get("p95").asLong());
        assertThat(trend.get("points").get(1).get("virtualUsers").asInt()).isEqualTo(1);
    }

    @Test
    void latestByScenario_paginatedOnTheServer_newestFirst_withFilters() throws Exception {
        latencyMs = 1;
        Instant start = Instant.now().minusSeconds(1);
        String appId = app("dash-pages", null);
        String other = app("dash-pages-autre", null);
        List<String> runs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            runs.add(run(scenario(appId, "Page-" + i + "-" + UUID.randomUUID().toString().substring(0, 4), "/ok")));
        }
        String otherRun = run(scenario(other, "Autre-" + UUID.randomUUID().toString().substring(0, 4), "/ok"));
        // Un scenario relance : une seule ligne (sa derniere execution), remontee en tete.
        String firstScenario = json(get("/api/executions/" + runs.get(0)), null).get("scenarioId").asText();
        String rerun = run(firstScenario);

        // Application filtree, 5 par page : 12 scenarios, 3 pages (5, 5, 2), la plus recente d'abord, sans doublon.
        List<String> seen = new ArrayList<>();
        for (int p = 0; p < 3; p++) {
            JsonNode page = latest(appId, start, null, p, 5);
            assertThat(page.get("totalElements").asLong()).isEqualTo(12);
            assertThat(page.get("totalPages").asInt()).isEqualTo(3);
            assertThat(page.get("page").asInt()).isEqualTo(p);
            assertThat(page.get("content").size()).isEqualTo(p < 2 ? 5 : 2);
            page.get("content").forEach(r -> seen.add(r.get("executionId").asText()));
        }
        assertThat(seen).doesNotHaveDuplicates().hasSize(12).doesNotContain(runs.get(0), otherRun);
        assertThat(seen.get(0)).as("scenario relance en tete").isEqualTo(rerun);
        List<String> expectedOrder = new ArrayList<>(runs.subList(1, 12));
        java.util.Collections.reverse(expectedOrder);
        assertThat(seen.subList(1, 12)).as("du plus recent au plus ancien").isEqualTo(expectedOrder);

        // Defaut : 10 par page ; bornes de taille et de page.
        JsonNode byDefault = latest(appId, start, null, null, null);
        assertThat(byDefault.get("content").size()).isEqualTo(10);
        assertThat(byDefault.get("totalPages").asInt()).isEqualTo(2);
        assertThat(latest(appId, start, null, 0, 5000).get("size").asInt()).isEqualTo(100);
        assertThat(latest(appId, start, null, -2, 0).get("page").asInt()).isZero();
        assertThat(latest(appId, start, null, 9, 10).get("content")).isEmpty();

        // Toutes les applications : l'autre application est comptee aussi.
        assertThat(latest(null, start, null, 0, 100).get("content").findValuesAsText("executionId")).contains(otherRun, rerun);
        // Periode : seulement la relance (lancee apres la 12e execution).
        Instant beforeRerun = Instant.parse(json(get("/api/executions/" + rerun + "/report"), null).get("startedAt").asText());
        JsonNode recentOnly = latest(appId, beforeRerun, null, null, null);
        assertThat(recentOnly.get("totalElements").asLong()).isEqualTo(1);
        assertThat(recentOnly.get("content").get(0).get("executionId").asText()).isEqualTo(rerun);
        assertThat(recentOnly.get("content").get(0).get("previousExecutionId").asText()).as("comparaison conservee").isEqualTo(runs.get(0));
        latencyMs = 10;
    }

    @Test
    void emptyPeriod_andMonitoringDown_andFailedApplication() throws Exception {
        JsonNode empty = overview(null, Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200));
        assertThat(empty.get("kpis").get("executions").asLong()).isZero();
        assertThat(empty.get("kpis").get("p95Ms").isNull()).isTrue();
        assertThat(empty.get("kpis").get("errorRate").isNull()).isTrue();
        assertThat(empty.get("scenarios")).isEmpty();
        JsonNode emptyRows = latest(null, Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200), null, null);
        assertThat(emptyRows.get("content")).isEmpty();
        assertThat(emptyRows.get("totalElements").asLong()).isZero();
        assertThat(empty.get("slowestSteps")).isEmpty();

        int closed;
        try (ServerSocket s = new ServerSocket(0)) {
            closed = s.getLocalPort();
        }
        String monitored = app("dash-mon", Map.of("type", "PROMETHEUS", "url", "http://localhost:" + closed + "/metrics",
                "authType", "NONE", "intervalSeconds", 1));
        Instant start = Instant.now().minusSeconds(1);
        run(scenario(monitored, "Mon-" + UUID.randomUUID().toString().substring(0, 6), "/ok"));
        assertThat(kinds(overview(monitored, start, null))).anyMatch(k -> k.startsWith("MONITORING_DOWN:Application « dash-mon-"));

        // Application dont le test de connexion echoue : signalee meme sans execution sur la periode.
        String down = json(post("/api/applications"), Map.of("name", "dash-down-" + UUID.randomUUID(),
                "url", "http://localhost:" + closed)).get("id").asText();
        perform(post("/api/applications/" + down + "/test"), null);
        JsonNode d = overview(down, null, null);
        assertThat(kinds(d)).anyMatch(k -> k.startsWith("APPLICATION_FAILED:Application « dash-down-"));
        assertThat(d.get("watchItems").get(0).get("link").asText()).startsWith("/applications?q=dash-down-");
    }

    @Test
    void rules_regressionAndSmallChange_inOnePlace() {
        assertThat(DashboardRules.changePercent(120L, 100L)).isEqualTo(20.0);
        assertThat(DashboardRules.isRegression(20.0)).as("strictement plus de 20 %").isFalse();
        assertThat(DashboardRules.isRegression(20.1)).isTrue();
        assertThat(DashboardRules.trend(4.9)).isEqualTo("SAME");
        assertThat(DashboardRules.trend(-12.0)).isEqualTo("BETTER");
        assertThat(DashboardRules.trend(6.0)).isEqualTo("WORSE");
        assertThat(DashboardRules.trend(null)).isEqualTo("NONE");
        assertThat(DashboardRules.changePercent(120L, null)).isNull();
        // Textes du serveur au format francais : virgule, espace des milliers, au plus une decimale.
        assertThat(DashboardRules.format(2.44)).isEqualTo("2,4");
        assertThat(DashboardRules.format(1234L)).matches("1[\\s\\u00A0\\u202F]234");
    }
}
