package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.dto.request.ApplicationRequest;
import com.loadpilot.backend.dto.request.ExecutionRequest;
import com.loadpilot.backend.dto.request.ScenarioRequest;
import com.loadpilot.backend.dto.request.ScheduledExecutionRequest;
import com.loadpilot.backend.dto.request.StepRequest;
import com.loadpilot.backend.enums.HttpMethod;
import com.loadpilot.backend.enums.ScheduleType;
import com.loadpilot.backend.repository.ScheduledExecutionRepository;
import com.loadpilot.backend.service.impl.ScheduledExecutionPoller;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * P1-B (prompt section 44) — un declenchement de ScheduledExecution
 * ("run now" ici, meme chemin exactement que le poller automatique - voir
 * ScheduledExecutionTrigger) respecte EXACTEMENT la meme limite globale de
 * capacite que le lancement manuel (P0-B, RunningExecutionRegistry) -
 * jamais un contournement. Contexte Spring DEDIE avec une limite
 * volontairement basse (meme technique que ExecutionConcurrencyLimitsTest).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "app.execution.max-concurrent-executions=1",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScheduledExecutionCapacityLimitTest {

    private static HttpServer targetServer;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ScheduledExecutionRepository scheduleRepository;
    @Autowired
    private ScheduledExecutionPoller poller;
    @MockBean
    private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_SUPER_ADMIN =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
    private static final RequestPostProcessor AS_VIEWER =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"));

    @BeforeAll
    static void startTargetServer() throws IOException {
        targetServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
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

    private String createScenario(String prefix, String path) throws Exception {
        String appBody = objectMapper.writeValueAsString(new ApplicationRequest(prefix + "-app", "d", targetBaseUrl()));
        String appResponse = mockMvc.perform(post("/api/applications").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(appBody))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String appId = objectMapper.readTree(appResponse).get("id").asText();

        String scenarioBody = objectMapper.writeValueAsString(new ScenarioRequest(UUID.fromString(appId), prefix + "-scenario", "d"));
        String scenarioResponse = mockMvc.perform(post("/api/scenarios").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(scenarioBody))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String scenarioId = objectMapper.readTree(scenarioResponse).get("id").asText();

        String stepBody = objectMapper.writeValueAsString(
                new StepRequest(UUID.fromString(scenarioId), prefix + "-step", HttpMethod.GET, path, null, null, 1, 200,
                        null, null, null, null, null, null));
        mockMvc.perform(post("/api/steps").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content(stepBody))
                .andExpect(status().isCreated());
        return scenarioId;
    }

    private String currentStatus(String executionId) throws Exception {
        String response = mockMvc.perform(get("/api/executions/" + executionId).with(AS_VIEWER))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return objectMapper.readTree(response).get("status").asText();
    }

    private void waitForStatus(String executionId, String expectedStatus) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (currentStatus(executionId).equals(expectedStatus)) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Execution " + executionId + " n'a pas atteint " + expectedStatus + " a temps.");
    }

    @Test
    void runNow_whileCapacityIsFull_returns429_andRecordsScheduleFailedNotificationAndError() throws Exception {
        // Occupe l'unique slot de capacite avec une execution manuelle lente.
        String occupyingScenarioId = createScenario("sched-capacity-occupy-" + UUID.randomUUID(), "/slow");
        String occupyingBody = objectMapper.writeValueAsString(new ExecutionRequest(UUID.fromString(occupyingScenarioId)));
        String occupyingResponse = mockMvc.perform(post("/api/executions").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(occupyingBody))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String occupyingExecutionId = objectMapper.readTree(occupyingResponse).get("id").asText();
        waitForStatus(occupyingExecutionId, "RUNNING");

        // Cree puis declenche une planification pendant que la capacite est pleine.
        String scenarioId = createScenario("sched-capacity-target-" + UUID.randomUUID(), "/ok");
        var request = new ScheduledExecutionRequest(UUID.fromString(scenarioId), "capacity-schedule",
                ScheduleType.ONE_TIME, null, Instant.now().plus(1, ChronoUnit.DAYS), "UTC");
        String scheduleResponse = mockMvc.perform(post("/api/scheduled-executions").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String scheduleId = objectMapper.readTree(scheduleResponse).get("id").asText();

        mockMvc.perform(post("/api/scheduled-executions/" + scheduleId + "/run-now").with(AS_SUPER_ADMIN))
                .andExpect(status().isTooManyRequests());

        String afterResponse = mockMvc.perform(get("/api/scheduled-executions/" + scheduleId).with(AS_SUPER_ADMIN))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode after = objectMapper.readTree(afterResponse);
        assertThat(after.get("lastTriggerError").asText()).isNotBlank();
        assertThat(after.get("lastExecutionId").isNull()).isTrue();

        // Notification SCHEDULE_FAILED bien emise pour le proprietaire de la planification.
        String notifResponse = mockMvc.perform(get("/api/notifications").with(AS_SUPER_ADMIN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode content = objectMapper.readTree(notifResponse).get("content");
        boolean found = false;
        for (JsonNode n : content) {
            if (n.get("type").asText().equals("SCHEDULE_FAILED") && n.get("relatedScheduleId").asText().equals(scheduleId)) {
                found = true;
                break;
            }
        }
        assertThat(found).as("Une notification SCHEDULE_FAILED doit exister pour ce declenchement refuse").isTrue();

        waitForStatus(occupyingExecutionId, "SUCCESS");
    }

    @Test
    void automaticTrigger_whileCapacityIsFull_isSkippedAndRecordedInHistory() throws Exception {
        String occupyingScenarioId = createScenario("sched-capacity-auto-occupy-" + UUID.randomUUID(), "/slow");
        String occupyingResponse = mockMvc.perform(post("/api/executions").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ExecutionRequest(UUID.fromString(occupyingScenarioId)))))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String occupyingExecutionId = objectMapper.readTree(occupyingResponse).get("id").asText();
        waitForStatus(occupyingExecutionId, "RUNNING");

        String scenarioId = createScenario("sched-capacity-auto-target-" + UUID.randomUUID(), "/ok");
        var request = new ScheduledExecutionRequest(UUID.fromString(scenarioId), "Test nocturne capacite",
                ScheduleType.RECURRING_CRON, "0 2 * * *", null, "UTC");
        String scheduleId = objectMapper.readTree(mockMvc.perform(post("/api/scheduled-executions").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("id").asText();
        var schedule = scheduleRepository.findById(UUID.fromString(scheduleId)).orElseThrow();
        schedule.setNextRunAt(Instant.now().minusSeconds(5));
        scheduleRepository.saveAndFlush(schedule);

        poller.pollAndTrigger();

        JsonNode history = objectMapper.readTree(mockMvc.perform(get("/api/scheduled-executions/" + scheduleId + "/history").with(AS_VIEWER))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("items");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("kind").asText()).isEqualTo("EVENT");
        assertThat(history.get(0).get("outcome").asText()).isEqualTo("SKIPPED_CAPACITY");
        assertThat(history.get(0).get("reason").asText()).startsWith("Ignorée : capacité insuffisante");
        // Recurrente : reprogrammee normalement, pas de rattrapage.
        assertThat(scheduleRepository.findById(UUID.fromString(scheduleId)).orElseThrow().getNextRunAt()).isAfter(Instant.now());

        JsonNode notifications = objectMapper.readTree(mockMvc.perform(get("/api/notifications").with(AS_SUPER_ADMIN))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("content");
        boolean found = false;
        for (JsonNode n : notifications) {
            if (n.get("relatedScheduleId").asText().equals(scheduleId)
                    && n.get("title").asText().equals("Test nocturne capacite : ignoré · capacité insuffisante")) found = true;
        }
        assertThat(found).isTrue();

        waitForStatus(occupyingExecutionId, "SUCCESS");
    }
}