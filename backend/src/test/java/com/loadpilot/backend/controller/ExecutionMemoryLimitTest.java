package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.ExecutionStepResultRepository;
import com.loadpilot.backend.service.execution.ErrorRateBrake;
import com.loadpilot.backend.service.impl.ResultsMemoryGuard;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Garde-fou memoire de bout en bout, avec une limite basse (300 resultats). Les resultats etant ecrits
 * en base pendant le test, seul le tampon d'ecriture compte : un grand test qui ecrit normalement va au
 * bout ; un tampon qui depasse la limite (base lente) arrete le test (Annulee, motif distinct), ses
 * resultats deja obtenus etant finalises et ecrits en base ; aucun arret sous la limite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "app.execution.max-results-in-memory=300")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExecutionMemoryLimitTest {

    private static HttpServer server;
    private static String targetUrl;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ExecutionRepository executionRepository;
    @Autowired
    private ExecutionStepResultRepository resultRepository;
    @Autowired
    private ResultsMemoryGuard guard;
    @MockBean
    private JwtDecoder jwtDecoder;
    /** Ecriture des lots (vraie), ralentie a la demande pour simuler une base lente. */
    @org.springframework.boot.test.mock.mockito.SpyBean
    private com.loadpilot.backend.service.impl.ExecutionTransactionHelper transactionHelper;
    private static final java.util.concurrent.atomic.AtomicInteger slowBatches = new java.util.concurrent.atomic.AtomicInteger();

    private static final RequestPostProcessor AS_ENGINEER = jwt().jwt(j -> j.subject("memory-engineer"))
            .authorities(new SimpleGrantedAuthority("ROLE_PERFORMANCE_ENGINEER"));

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", ex -> {
            ex.sendResponseHeaders(200, -1);
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

    private JsonNode call(MockHttpServletRequestBuilder builder, Object body) throws Exception {
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        String json = mockMvc.perform(builder.with(AS_ENGINEER)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.isBlank() ? null : objectMapper.readTree(json);
    }

    private String launch(String appId, Map<String, Object> load) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("applicationId", appId, "name", "Memoire " + UUID.randomUUID(),
                "rampUpSeconds", 0, "thinkTimeMs", 0));
        body.putAll(load);
        String id = call(post("/api/scenarios"), body).get("id").asText();
        call(post("/api/steps"), Map.of("scenarioId", id, "name", "Appel", "method", "GET", "url", "/ok", "order", 1));
        return call(post("/api/executions"), Map.of("scenarioId", id)).get("id").asText();
    }

    private String awaitEnd(String executionId) throws Exception {
        long deadline = System.currentTimeMillis() + 25_000;
        String status = "";
        while (System.currentTimeMillis() < deadline) {
            status = call(get("/api/executions/" + executionId + "/status"), null).get("status").asText();
            if (!List.of("QUEUED", "RUNNING", "PAUSED").contains(status)) return status;
            Thread.sleep(50);
        }
        return status;
    }

    private JsonNode row(String appId, String executionId) throws Exception {
        for (JsonNode r : call(get("/api/executions/page").param("applicationId", appId).param("size", "50"), null).get("content")) {
            if (r.get("id").asText().equals(executionId)) return r;
        }
        return null;
    }

    @Test
    void resultsWrittenDuringTheTest_noLongerCount_aLargeTestGoesToTheEnd() throws Exception {
        assertThat(guard.maxResults()).isEqualTo(300);
        String appId = call(post("/api/applications"), Map.of("name", "memoire-grand-" + UUID.randomUUID(), "url", targetUrl)).get("id").asText();
        // 2 VU sans temps de reflexion pendant 3 s : bien plus de 300 resultats, ecrits en base au fil du test.
        String big = launch(appId, Map.of("virtualUsers", 2, "durationSeconds", 3));
        assertThat(awaitEnd(big)).isEqualTo("SUCCESS");
        Execution e = executionRepository.findById(UUID.fromString(big)).orElseThrow();
        long saved = resultRepository.countAllByExecutionId(e.getId());
        assertThat(saved).isGreaterThan(300);
        assertThat((long) e.getSuccessfulSteps()).isEqualTo(saved);
    }

    @Test
    void writeBufferOverTheLimit_theTestStops_andItsResultsAreFinalizedNormally() throws Exception {
        assertThat(guard.maxResults()).isEqualTo(300);
        String appId = call(post("/api/applications"), Map.of("name", "memoire-" + UUID.randomUUID(), "url", targetUrl)).get("id").asText();
        // Base lente (3 premiers lots, 1,5 s chacun) : les resultats s'accumulent dans le tampon d'ecriture.
        slowBatches.set(3);
        doAnswer(invocation -> {
            if (slowBatches.getAndDecrement() > 0) Thread.sleep(1500);
            return invocation.callRealMethod();
        }).when(transactionHelper).appendResults(any(), any(), any());

        // 2 VU sans temps de reflexion pendant 30 s : tampon au-dela de 300 en quelques instants.
        long started = System.currentTimeMillis();
        String stopped = launch(appId, Map.of("virtualUsers", 2, "durationSeconds", 30));
        assertThat(awaitEnd(stopped)).isEqualTo("CANCELLED");
        assertThat(System.currentTimeMillis() - started).as("arrete bien avant les 30 s prevues").isLessThan(20_000);

        Execution e = executionRepository.findById(UUID.fromString(stopped)).orElseThrow();
        assertThat(e.getErrorMessage()).startsWith(ResultsMemoryGuard.REASON_PREFIX)
                .doesNotStartWith(ErrorRateBrake.REASON_PREFIX + "0").contains("Les résultats déjà obtenus sont conservés.");
        assertThat(e.getCancelledBy()).as("pas une annulation manuelle").isNull();
        // Resultats deja obtenus : ecrits en base et comptes, comme pour une annulation manuelle.
        long saved = resultRepository.findByExecutionIdOrderByTimestampAsc(e.getId()).size();
        assertThat(saved).isGreaterThanOrEqualTo(300);
        assertThat((long) e.getSuccessfulSteps() + e.getFailedSteps()).isEqualTo(saved);
        assertThat(e.getSuccessfulSteps()).isPositive();
        assertThat(row(appId, stopped).get("statusReason").asText()).isEqualTo("Arrêt automatique : limite mémoire");
    }

    @Test
    void manualCancel_alsoKeepsItsResults() throws Exception {
        String appId = call(post("/api/applications"), Map.of("name", "annule-" + UUID.randomUUID(), "url", targetUrl)).get("id").asText();
        // 1 VU, temps de reflexion 50 ms : ~20 resultats par seconde, sous la limite de 300.
        String running = launch(appId, Map.of("virtualUsers", 1, "durationSeconds", 30, "thinkTimeMs", 50));
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline
                && call(get("/api/executions/" + running + "/status"), null).get("totalRequests").asLong() < 10) Thread.sleep(50);
        call(post("/api/executions/" + running + "/cancel"), null);
        assertThat(awaitEnd(running)).isEqualTo("CANCELLED");

        Execution e = executionRepository.findById(UUID.fromString(running)).orElseThrow();
        assertThat(e.getCancelledBy()).isEqualTo("memory-engineer");
        long saved = resultRepository.findByExecutionIdOrderByTimestampAsc(e.getId()).size();
        assertThat(saved).isGreaterThanOrEqualTo(10).isLessThan(300);
        assertThat((long) e.getSuccessfulSteps()).as("aucun faux echec du a l'annulation").isEqualTo(saved);
        assertThat(e.getFailedSteps()).isZero();
    }

    @Test
    void underTheLimit_theTestGoesToTheEnd() throws Exception {
        String appId = call(post("/api/applications"), Map.of("name", "memoire-ok-" + UUID.randomUUID(), "url", targetUrl)).get("id").asText();
        String normal = launch(appId, Map.of("virtualUsers", 2, "iterations", 50)); // 100 resultats
        assertThat(awaitEnd(normal)).isEqualTo("SUCCESS");
        Execution e = executionRepository.findById(UUID.fromString(normal)).orElseThrow();
        assertThat(e.getSuccessfulSteps()).isEqualTo(100);
        assertThat(e.getErrorMessage()).isNull();
    }
}
