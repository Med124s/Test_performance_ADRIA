package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.repository.ExecutionRequestDetailRepository;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Detail des requetes d'une vraie execution : echantillon (3 premieres iterations par etape et
 * par VU), echecs detailles, liste paginee filtree, detail complet avec secrets masques et corps
 * tronque, mode NONE, suppression du detail avec l'execution.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExecutionRequestDetailTest {

    private static HttpServer server;
    private static String targetUrl;
    private static final AtomicInteger CALLS = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ExecutionRequestDetailRepository detailRepository;
    @MockBean
    private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_ENGINEER = jwt().authorities(new SimpleGrantedAuthority("ROLE_PERFORMANCE_ENGINEER"));

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/login", ex -> reply(ex, 200, "{\"token\":\"SESSION-ABCDEF-123\",\"user\":\"agent001\"}"));
        server.createContext("/data", ex -> {
            // Un appel sur 4 echoue (409), pour avoir des echecs detailles.
            int n = CALLS.incrementAndGet();
            reply(ex, n % 4 == 0 ? 409 : 200, "{\"items\":[1,2,3],\"echo\":\"" + ex.getRequestURI().getQuery() + "\"}");
        });
        server.createContext("/big", ex -> reply(ex, 200, "{\"blob\":\"" + "y".repeat(30_000) + "\"}"));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        targetUrl = "http://localhost:" + server.getAddress().getPort();
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        ex.getRequestBody().readAllBytes();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.getResponseHeaders().add("Set-Cookie", "SESSIONID=cookie-secret-value; HttpOnly");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private JsonNode call(MockHttpServletRequestBuilder builder, Object body) throws Exception {
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        String json = mockMvc.perform(builder.with(AS_ENGINEER)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.isBlank() ? null : objectMapper.readTree(json);
    }

    private int status(MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder.with(AS_ENGINEER)).andReturn().getResponse().getStatus();
    }

    private String[] scenario(String mode) throws Exception {
        String appId = call(post("/api/applications"), Map.of("name", "detail-" + UUID.randomUUID(), "url", targetUrl)).get("id").asText();
        Map<String, Object> body = new HashMap<>(Map.of("applicationId", appId, "name", "Detail " + mode, "virtualUsers", 2,
                "rampUpSeconds", 0, "iterations", 5, "thinkTimeMs", 0,
                "csvData", "username,password\nagent001,motdepasse-csv\n"));
        if (mode != null) body.put("requestDetailMode", mode);
        String scenarioId = call(post("/api/scenarios"), body).get("id").asText();
        String login = call(post("/api/steps"), Map.of("scenarioId", scenarioId, "name", "Connexion", "method", "POST", "url", "/login",
                "order", 1, "headers", "{\"Content-Type\":\"application/json\"}",
                "body", "{\"username\":\"${username}\",\"password\":\"${password}\"}",
                "captureVariableName", "token", "captureJsonPath", "$.token")).get("id").asText();
        String data = call(post("/api/steps"), Map.of("scenarioId", scenarioId, "name", "Donnees", "method", "GET",
                "url", "/data?user=${username}", "order", 2, "headers", "{\"Authorization\":\"Bearer ${token}\",\"X-Auth-Token\":\"${token}\"}",
                "expectedStatus", 200, "assertionBodyContains", "items")).get("id").asText();
        String big = call(post("/api/steps"), Map.of("scenarioId", scenarioId, "name", "Gros corps", "method", "GET", "url", "/big",
                "order", 3)).get("id").asText();
        return new String[] {scenarioId, login, data, big};
    }

    private String runToEnd(String scenarioId) throws Exception {
        String executionId = call(post("/api/executions"), Map.of("scenarioId", scenarioId)).get("id").asText();
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            String st = call(get("/api/executions/" + executionId + "/status"), null).get("status").asText();
            if (List.of("SUCCESS", "FAILED", "CANCELLED").contains(st)) break;
            Thread.sleep(100);
        }
        Thread.sleep(500);
        return executionId;
    }

    @Test
    void sampleAndFailures_areDetailed_listIsPagedAndFiltered_secretsMasked_bodyTruncated() throws Exception {
        String[] ids = scenario(null); // defaut : echecs + echantillon
        String executionId = runToEnd(ids[0]);

        JsonNode summary = call(get("/api/executions/" + executionId + "?includeResults=false"), null);
        assertThat(summary.get("results")).isEmpty();
        assertThat(summary.get("requestDetailMode").asText()).isEqualTo("FAILURES_AND_SAMPLE");
        assertThat(summary.get("detailSampleCapReached").asBoolean()).isFalse();
        assertThat(summary.get("detailedSamples").asLong()).isPositive();
        assertThat(summary.get("detailedFailures").asLong()).isPositive();

        // VU 1, etape Connexion : 5 iterations, detail pour les 3 premieres seulement.
        JsonNode vu0 = call(get("/api/executions/" + executionId + "/results").param("stepId", ids[1]).param("virtualUser", "0"), null);
        assertThat(vu0.get("totalElements").asLong()).isEqualTo(5);
        for (JsonNode row : vu0.get("content")) {
            int it = row.get("iteration").asInt();
            assertThat(row.get("detailKind").isNull()).as("iteration " + it).isEqualTo(it >= 3);
        }
        JsonNode it2 = call(get("/api/executions/" + executionId + "/results").param("stepId", ids[1]).param("iteration", "1"), null);
        assertThat(it2.get("totalElements").asLong()).isEqualTo(2);

        // Echecs de l'etape Donnees : detailles (FAILURE) meme au-dela de la 3e iteration.
        JsonNode failures = call(get("/api/executions/" + executionId + "/results").param("stepId", ids[2]).param("failedOnly", "true"), null);
        assertThat(failures.get("totalElements").asLong()).isPositive();
        failures.get("content").forEach(r -> assertThat(r.get("detailKind").asText()).isEqualTo("FAILURE"));
        JsonNode paged = call(get("/api/executions/" + executionId + "/results").param("size", "5").param("page", "1"), null);
        assertThat(paged.get("content")).hasSize(5);
        // Une iteration s'arrete a la premiere etape en echec : entre 20 et 30 requetes au total.
        assertThat(paged.get("totalElements").asLong()).isBetween(20L, 30L);

        // Detail d'une connexion : secrets masques partout.
        String loginResult = vu0.get("content").get(0).get("resultId").asText();
        JsonNode d = call(get("/api/executions/" + executionId + "/results/" + loginResult + "/detail"), null);
        String all = d.toString();
        assertThat(all).doesNotContain("motdepasse-csv").doesNotContain("SESSION-ABCDEF-123").doesNotContain("cookie-secret-value");
        assertThat(d.get("request").get("body").asText()).contains("\"username\":\"agent001\"").contains("\"password\":\"••••••\"");
        assertThat(d.get("response").get("body").asText()).contains("\"token\":\"••••••\"");
        assertThat(d.get("checks").get("captureName").asText()).isEqualTo("token");
        assertThat(d.get("checks").get("captureFound").asBoolean()).isTrue();
        assertThat(d.get("checks").get("captureValue").asText()).isEqualTo("••••••");

        // Detail d'une requete Donnees : URL finale avec variables remplacees, en-tetes sensibles masques.
        String failedResult = failures.get("content").get(0).get("resultId").asText();
        JsonNode df = call(get("/api/executions/" + executionId + "/results/" + failedResult + "/detail"), null);
        assertThat(df.get("request").get("url").asText()).endsWith("/data?user=agent001");
        assertThat(df.get("request").get("headers").get("Authorization").asText()).isEqualTo("••••••");
        assertThat(df.get("request").get("headers").get("X-Auth-Token").asText()).isEqualTo("••••••");
        assertThat(df.get("response").get("status").asInt()).isEqualTo(409);
        assertThat(df.get("checks").get("expectedStatus").asText()).isEqualTo("200");
        assertThat(df.get("checks").get("statusOk").asBoolean()).isFalse();
        assertThat(df.toString()).doesNotContain("SESSION-ABCDEF-123");

        // Gros corps : 10 Ko conserves, taille reelle indiquee.
        // (une iteration peut s'arreter avant cette etape si « Donnees » echoue : premiere requete detaillee de l'etape)
        JsonNode bigRows = call(get("/api/executions/" + executionId + "/results").param("stepId", ids[3]).param("size", "50"), null);
        String bigResult = null;
        for (JsonNode row : bigRows.get("content")) {
            if (!row.get("detailKind").isNull()) {
                bigResult = row.get("resultId").asText();
                break;
            }
        }
        assertThat(bigResult).as("au moins une requete Gros corps detaillee").isNotNull();
        JsonNode db = call(get("/api/executions/" + executionId + "/results/" + bigResult + "/detail"), null);
        assertThat(db.get("response").get("bodyTruncated").asBoolean()).isTrue();
        assertThat(db.get("response").get("sizeBytes").asLong()).isGreaterThan(30_000);
        assertThat(db.get("response").get("body").asText().length()).isLessThanOrEqualTo(10 * 1024);

        // Requete non detaillee : 404 explicite.
        String plain = vu0.get("content").get(4).get("resultId").asText();
        assertThat(status(get("/api/executions/" + executionId + "/results/" + plain + "/detail"))).isEqualTo(404);

        // Suppression de l'execution : le detail disparait avec elle.
        UUID execUuid = UUID.fromString(executionId);
        assertThat(detailRepository.countByExecutionId(execUuid)).isPositive();
        assertThat(status(delete("/api/executions/" + executionId))).isEqualTo(204);
        assertThat(detailRepository.countByExecutionId(execUuid)).isZero();
    }

    @Test
    void modeNone_keepsNoDetail_butTheListStillWorks() throws Exception {
        String[] ids = scenario("NONE");
        assertThat(call(get("/api/scenarios/" + ids[0]), null).get("requestDetailMode").asText()).isEqualTo("NONE");
        String executionId = runToEnd(ids[0]);
        assertThat(detailRepository.countByExecutionId(UUID.fromString(executionId))).isZero();
        JsonNode rows = call(get("/api/executions/" + executionId + "/results").param("stepId", ids[1]), null);
        assertThat(rows.get("totalElements").asLong()).isEqualTo(10);
        rows.get("content").forEach(r -> assertThat(r.get("detailKind").isNull()).isTrue());
    }
}
