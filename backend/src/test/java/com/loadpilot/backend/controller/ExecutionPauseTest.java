package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.service.impl.PausedExecutionWatchdog;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Pause / reprise d'une vraie execution : plus aucune requete pendant la pause, temps de pause
 * exclu de la duree de travail et du debit, annulation en pause, annulation automatique,
 * droits (lecteur refuse), statut PAUSED dans l'historique.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExecutionPauseTest {

    private static HttpServer server;
    private static String targetUrl;
    private static final AtomicInteger RECEIVED = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PausedExecutionWatchdog watchdog;
    @MockBean
    private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_ENGINEER = jwt().authorities(new SimpleGrantedAuthority("ROLE_PERFORMANCE_ENGINEER"));
    private static final RequestPostProcessor AS_VIEWER = jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"));

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", ex -> {
            RECEIVED.incrementAndGet();
            try {
                Thread.sleep(80);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
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

    private JsonNode status(String executionId) throws Exception {
        return call(get("/api/executions/" + executionId + "/status"), null);
    }

    private String startExecution(String name, int durationSeconds) throws Exception {
        String appId = call(post("/api/applications"), Map.of("name", name + "-app", "url", targetUrl)).get("id").asText();
        Map<String, Object> scenario = new HashMap<>(Map.of("applicationId", appId, "name", name,
                "virtualUsers", 2, "rampUpSeconds", 0, "durationSeconds", durationSeconds, "thinkTimeMs", 0));
        String scenarioId = call(post("/api/scenarios"), scenario).get("id").asText();
        call(post("/api/steps"), Map.of("scenarioId", scenarioId, "name", "Lire", "method", "GET", "url", "/ok", "order", 1));
        String executionId = call(post("/api/executions"), Map.of("scenarioId", scenarioId)).get("id").asText();
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode s = status(executionId);
            if ("RUNNING".equals(s.get("status").asText()) && s.hasNonNull("live") && s.get("live").get("requests").asLong() >= 2) {
                return executionId;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("execution jamais RUNNING");
    }

    private JsonNode waitTerminal(String executionId) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        JsonNode s;
        do {
            Thread.sleep(150);
            s = status(executionId);
        } while (System.currentTimeMillis() < deadline
                && java.util.List.of("QUEUED", "RUNNING", "PAUSED").contains(s.get("status").asText()));
        return s;
    }

    @Test
    void pauseStopsNewRequests_resumeContinues_pauseExcludedFromWorkTimeAndThroughput() throws Exception {
        String executionId = startExecution("Pause reprise", 2);

        assertThat(perform(post("/api/executions/" + executionId + "/pause"), null, AS_ENGINEER).getResponse().getStatus()).isEqualTo(200);
        JsonNode paused = status(executionId);
        assertThat(paused.get("status").asText()).isEqualTo("PAUSED");
        assertThat(paused.hasNonNull("pausedAt")).isTrue();

        // Les requetes deja en vol recoivent leur reponse, puis plus aucune nouvelle requete.
        Thread.sleep(300);
        int receivedAtPause = RECEIVED.get();
        long progressAtPause = status(executionId).get("progressPercent").asLong();
        Thread.sleep(1500);
        assertThat(RECEIVED.get()).as("aucune requete envoyee pendant la pause").isEqualTo(receivedAtPause);
        JsonNode stillPaused = status(executionId);
        assertThat(stillPaused.get("progressPercent").asLong()).as("progression figee").isEqualTo(progressAtPause);
        assertThat(stillPaused.get("pausedDurationMs").asLong()).isGreaterThanOrEqualTo(1700);

        // Deuxieme pause refusee ; l'execution en pause bloque les modifications du scenario.
        assertThat(perform(post("/api/executions/" + executionId + "/pause"), null, AS_ENGINEER).getResponse().getStatus()).isEqualTo(409);

        assertThat(perform(post("/api/executions/" + executionId + "/resume"), null, AS_ENGINEER).getResponse().getStatus()).isEqualTo(200);
        assertThat(status(executionId).get("status").asText()).isEqualTo("RUNNING");
        assertThat(perform(post("/api/executions/" + executionId + "/resume"), null, AS_ENGINEER).getResponse().getStatus()).isEqualTo(409);

        JsonNode done = waitTerminal(executionId);
        assertThat(done.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(RECEIVED.get()).as("le test a repris apres la pause").isGreaterThan(receivedAtPause);

        JsonNode report = call(get("/api/executions/" + executionId + "/report"), null);
        long duration = report.get("duration").asLong();
        long pausedMs = report.get("pausedDurationMs").asLong();
        assertThat(pausedMs).isBetween(1700L, 4000L);
        // Duree configuree 2 s : le travail reel reste ~2 s, la pause s'ajoute a la duree totale.
        assertThat(duration - pausedMs).as("temps de travail hors pause").isBetween(1800L, 3500L);
        assertThat(duration).isGreaterThanOrEqualTo(2000L + 1700L);
        double expectedThroughput = report.get("statistics").get("totalRequests").asDouble() / ((duration - pausedMs) / 1000.0);
        assertThat(report.get("statistics").get("throughput").asDouble()).isCloseTo(expectedThroughput, org.assertj.core.data.Offset.offset(0.05));
        assertThat(report.get("statistics").has("stdDevResponseTime")).isFalse();
    }

    @Test
    void cancelWhilePaused_historyFilter_andRoles() throws Exception {
        String executionId = startExecution("Pause annulee", 30);

        // Lecteur : ni pause ni reprise.
        assertThat(perform(post("/api/executions/" + executionId + "/pause"), null, AS_VIEWER).getResponse().getStatus()).isEqualTo(403);
        assertThat(perform(post("/api/executions/" + executionId + "/pause"), null, AS_ENGINEER).getResponse().getStatus()).isEqualTo(200);
        assertThat(perform(post("/api/executions/" + executionId + "/resume"), null, AS_VIEWER).getResponse().getStatus()).isEqualTo(403);

        // Statut PAUSED visible et filtrable dans l'historique.
        JsonNode history = call(get("/api/executions/history?status=PAUSED&size=50"), null);
        assertThat(history.get("content").findValuesAsText("id")).contains(executionId);

        // Toujours annulable en pause.
        assertThat(perform(post("/api/executions/" + executionId + "/cancel"), null, AS_ENGINEER).getResponse().getStatus()).isEqualTo(200);
        JsonNode done = waitTerminal(executionId);
        assertThat(done.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(done.get("pausedAt").isNull()).isTrue();
        assertThat(done.get("pausedDurationMs").asLong()).isPositive();
    }

    @Test
    void pauseTooLong_isCancelledAutomaticallyWithClearMessage() throws Exception {
        String executionId = startExecution("Pause oubliee", 30);
        assertThat(perform(post("/api/executions/" + executionId + "/pause"), null, AS_ENGINEER).getResponse().getStatus()).isEqualTo(200);

        // Equivalent d'une pause de plus de 15 minutes (seuil dans le futur).
        assertThat(watchdog.cancelPausedBefore(Instant.now().plusSeconds(60))).isGreaterThanOrEqualTo(1);

        JsonNode done = waitTerminal(executionId);
        assertThat(done.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(done.get("errorMessage").asText()).isEqualTo(PausedExecutionWatchdog.timeoutMessage(15));
    }

    @Test
    void pauseRefused_whenFinished_withClearMessage() throws Exception {
        String executionId = startExecution("Pause terminee", 1);
        waitTerminal(executionId);

        MvcResult pause = perform(post("/api/executions/" + executionId + "/pause"), null, AS_ENGINEER);
        assertThat(pause.getResponse().getStatus()).isEqualTo(409);
        assertThat(objectMapper.readTree(pause.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("message").asText())
                .isEqualTo("Cette exécution est déjà terminée, elle ne peut plus être mise en pause.");

        MvcResult resume = perform(post("/api/executions/" + executionId + "/resume"), null, AS_ENGINEER);
        assertThat(resume.getResponse().getStatus()).isEqualTo(409);
        assertThat(objectMapper.readTree(resume.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("message").asText())
                .isEqualTo("Cette exécution est déjà terminée, elle ne peut plus être reprise.");
    }

    @Test
    void pauseOnUnknownExecution_is404WithMessage_notARawRouteError() throws Exception {
        MvcResult result = perform(post("/api/executions/00000000-0000-0000-0000-000000000000/pause"), null, AS_ENGINEER);
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("message").asText())
                .startsWith("Exécution introuvable");
    }
}
