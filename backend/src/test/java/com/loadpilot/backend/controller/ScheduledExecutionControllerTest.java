package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.dto.request.ApplicationRequest;
import com.loadpilot.backend.dto.request.ScenarioRequest;
import com.loadpilot.backend.dto.request.ScheduledExecutionRequest;
import com.loadpilot.backend.dto.request.StepRequest;
import com.loadpilot.backend.entity.ScheduledExecution;
import com.loadpilot.backend.enums.HttpMethod;
import com.loadpilot.backend.enums.ScheduleType;
import com.loadpilot.backend.repository.ScheduledExecutionRepository;
import com.loadpilot.backend.service.execution.TriggerClaim;
import com.loadpilot.backend.service.impl.ScheduledExecutionPoller;
import com.loadpilot.backend.service.impl.ScheduledExecutionTransactionHelper;
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
 * P1-B — verifie /api/scheduled-executions de bout en bout : validation
 * croisee (cron/timezone/runAt), permissions (meme matrice que
 * /api/executions), declenchement REEL via run-now (meme moteur que le
 * lancement manuel), et LA protection anti double-declenchement
 * transactionnelle (voir ScheduledExecutionTransactionHelper).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScheduledExecutionControllerTest {

    private static HttpServer targetServer;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ScheduledExecutionRepository scheduledExecutionRepository;

    @Autowired
    private ScheduledExecutionTransactionHelper transactionHelper;

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
        targetServer.createContext("/ok", exchange -> {
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

    private String createScenario(String prefix) throws Exception {
        String appBody = objectMapper.writeValueAsString(new ApplicationRequest(prefix + "-app", "d", targetBaseUrl()));
        String appResponse = mockMvc.perform(post("/api/applications").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(appBody))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String appId = objectMapper.readTree(appResponse).get("id").asText();

        String scenarioBody = objectMapper.writeValueAsString(new ScenarioRequest(UUID.fromString(appId), prefix + "-scenario", "d"));
        String scenarioResponse = mockMvc.perform(post("/api/scenarios").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(scenarioBody))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String scenarioId = objectMapper.readTree(scenarioResponse).get("id").asText();

        String stepBody = objectMapper.writeValueAsString(
                new StepRequest(UUID.fromString(scenarioId), prefix + "-step", HttpMethod.GET, "/ok", null, null, 1, 200,
                        null, null, null, null, null, null));
        mockMvc.perform(post("/api/steps").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content(stepBody))
                .andExpect(status().isCreated());
        return scenarioId;
    }

    private String createOneTimeSchedule(String scenarioId, String name, Instant runAt) throws Exception {
        var request = new ScheduledExecutionRequest(UUID.fromString(scenarioId), name, ScheduleType.ONE_TIME, null, runAt, "UTC");
        String response = mockMvc.perform(post("/api/scheduled-executions").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private void waitForExecutionSuccess(String executionId) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            String status = objectMapper.readTree(
                    mockMvc.perform(get("/api/executions/" + executionId).with(AS_VIEWER))
                            .andReturn().getResponse().getContentAsString()).get("status").asText();
            if (status.equals("SUCCESS")) return;
            if (status.equals("FAILED") || status.equals("CANCELLED")) {
                throw new AssertionError("Execution " + executionId + " a termine avec le statut inattendu " + status);
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Execution " + executionId + " n'a pas atteint SUCCESS a temps.");
    }

    // ------------------------------------------------------------
    // Securite
    // ------------------------------------------------------------

    @Test
    void list_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/scheduled-executions")).andExpect(status().isUnauthorized());
    }

    @Test
    void viewerRole_canListButNotCreateEnableDisableRunNowOrDelete() throws Exception {
        String scenarioId = createScenario("sched-viewer-sec");
        String id = createOneTimeSchedule(scenarioId, "viewer-sec-schedule", Instant.now().plus(1, ChronoUnit.DAYS));

        mockMvc.perform(get("/api/scheduled-executions").with(AS_VIEWER)).andExpect(status().isOk());
        mockMvc.perform(get("/api/scheduled-executions/" + id).with(AS_VIEWER)).andExpect(status().isOk());

        var request = new ScheduledExecutionRequest(UUID.fromString(scenarioId), "x", ScheduleType.ONE_TIME, null, Instant.now().plus(1, ChronoUnit.DAYS), "UTC");
        String body = objectMapper.writeValueAsString(request);
        mockMvc.perform(post("/api/scheduled-executions").with(AS_VIEWER).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/scheduled-executions/" + id).with(AS_VIEWER).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/scheduled-executions/" + id + "/enable").with(AS_VIEWER)).andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/scheduled-executions/" + id + "/disable").with(AS_VIEWER)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/scheduled-executions/" + id + "/run-now").with(AS_VIEWER)).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/scheduled-executions/" + id).with(AS_VIEWER)).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------

    @Test
    void create_oneTime_withPastRunAt_returns400() throws Exception {
        String scenarioId = createScenario("sched-past");
        var request = new ScheduledExecutionRequest(UUID.fromString(scenarioId), "past", ScheduleType.ONE_TIME, null, Instant.now().minus(1, ChronoUnit.HOURS), "UTC");
        mockMvc.perform(post("/api/scheduled-executions").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_recurringCron_withInvalidExpression_returns400() throws Exception {
        String scenarioId = createScenario("sched-badcron");
        var request = new ScheduledExecutionRequest(UUID.fromString(scenarioId), "badcron", ScheduleType.RECURRING_CRON, "not a cron", null, "UTC");
        mockMvc.perform(post("/api/scheduled-executions").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_nonexistentScenario_returns404() throws Exception {
        var request = new ScheduledExecutionRequest(UUID.randomUUID(), "x", ScheduleType.ONE_TIME, null, Instant.now().plus(1, ChronoUnit.DAYS), "UTC");
        mockMvc.perform(post("/api/scheduled-executions").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------
    // CRUD / cycle de vie
    // ------------------------------------------------------------

    @Test
    void create_oneTime_returnsScheduleWithComputedNextRunAt() throws Exception {
        String scenarioId = createScenario("sched-create");
        Instant runAt = Instant.now().plus(2, ChronoUnit.DAYS);
        String id = createOneTimeSchedule(scenarioId, "create-schedule", runAt);

        mockMvc.perform(get("/api/scheduled-executions/" + id).with(AS_SUPER_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenarioId").value(scenarioId))
                .andExpect(jsonPath("$.scheduleType").value("ONE_TIME"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.nextRunAt").exists())
                .andExpect(jsonPath("$.createdByUsername").exists());
    }

    @Test
    void update_recomputesNextRunAt() throws Exception {
        String scenarioId = createScenario("sched-update");
        String id = createOneTimeSchedule(scenarioId, "update-schedule", Instant.now().plus(1, ChronoUnit.DAYS));

        Instant newRunAt = Instant.now().plus(5, ChronoUnit.DAYS);
        var updateRequest = new ScheduledExecutionRequest(UUID.fromString(scenarioId), "update-schedule-renamed", ScheduleType.ONE_TIME, null, newRunAt, "UTC");
        mockMvc.perform(put("/api/scheduled-executions/" + id).with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("update-schedule-renamed"))
                .andExpect(jsonPath("$.nextRunAt").value(newRunAt.toString()));
    }

    @Test
    void disable_thenEnable_togglesEnabledFlag() throws Exception {
        String scenarioId = createScenario("sched-toggle");
        String id = createOneTimeSchedule(scenarioId, "toggle-schedule", Instant.now().plus(1, ChronoUnit.DAYS));

        mockMvc.perform(patch("/api/scheduled-executions/" + id + "/disable").with(AS_SUPER_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        mockMvc.perform(patch("/api/scheduled-executions/" + id + "/enable").with(AS_SUPER_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void delete_removesSchedule_andGetByIdReturns404() throws Exception {
        String scenarioId = createScenario("sched-delete");
        String id = createOneTimeSchedule(scenarioId, "delete-schedule", Instant.now().plus(1, ChronoUnit.DAYS));

        mockMvc.perform(delete("/api/scheduled-executions/" + id).with(AS_SUPER_ADMIN)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/scheduled-executions/" + id).with(AS_SUPER_ADMIN)).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------
    // Declenchement reel (run-now) — MEME moteur que le lancement manuel
    // ------------------------------------------------------------

    @Test
    void runNow_triggersARealExecution_disablesFutureAutoFiringForOneTime_andRecordsLastExecution() throws Exception {
        String scenarioId = createScenario("sched-runnow");
        // runAt tres loin dans le futur : ne doit JAMAIS etre declenchee
        // automatiquement pendant ce test, uniquement via "run now".
        String id = createOneTimeSchedule(scenarioId, "runnow-schedule", Instant.now().plus(30, ChronoUnit.DAYS));

        String response = mockMvc.perform(post("/api/scheduled-executions/" + id + "/run-now").with(AS_SUPER_ADMIN))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        String executionId = objectMapper.readTree(response).get("lastExecutionId").asText();
        assertThat(executionId).isNotBlank();
        waitForExecutionSuccess(executionId);

        mockMvc.perform(get("/api/scheduled-executions/" + id).with(AS_SUPER_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextRunAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.lastTriggeredAt").exists())
                .andExpect(jsonPath("$.lastExecutionId").value(executionId));
    }

    @Test
    void runNow_onUnknownId_returns404() throws Exception {
        mockMvc.perform(post("/api/scheduled-executions/" + UUID.randomUUID() + "/run-now").with(AS_SUPER_ADMIN))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------
    // Protection anti double-declenchement (transactionnelle, section 45)
    // ------------------------------------------------------------

    /**
     * Simule un tick de poller sur une ScheduledExecution RECURRING_CRON
     * DEJA due (nextRunAt force dans le passe directement en base - la
     * seule facon d'obtenir cet etat sans attendre reellement une
     * occurrence cron, l'API publique ne permettant jamais de creer une
     * planification deja due). Verifie que DEUX reclamations immediates
     * consecutives ne peuvent JAMAIS toutes les deux reussir : la seconde
     * doit systematiquement observer "nextRunAt" deja reprogramme dans le
     * futur par la premiere (verrou + reprogrammation immediate AVANT le
     * vrai declenchement, voir ScheduledExecutionTransactionHelper).
     */
    @Test
    void claimForAutomaticTrigger_calledTwiceForSameDueSchedule_onlyTheFirstSucceeds() throws Exception {
        String scenarioId = createScenario("sched-doubletrigger");
        var request = new ScheduledExecutionRequest(UUID.fromString(scenarioId), "double-trigger-schedule",
                ScheduleType.RECURRING_CRON, "0 0 8 * * *", null, "UTC");
        String response = mockMvc.perform(post("/api/scheduled-executions").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(response).get("id").asText());

        ScheduledExecution schedule = scheduledExecutionRepository.findById(id).orElseThrow();
        schedule.setNextRunAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        scheduledExecutionRepository.saveAndFlush(schedule);

        Instant now = Instant.now();
        TriggerClaim first = transactionHelper.claimForAutomaticTrigger(id, now);
        TriggerClaim second = transactionHelper.claimForAutomaticTrigger(id, now);

        assertThat(first.claimed()).isTrue();
        assertThat(second.claimed()).isFalse();

        // La premiere reclamation a bien reprogramme "nextRunAt" dans le
        // futur (recurrence cron) - c'est CE mecanisme qui empeche la
        // seconde de reussir, jamais un simple flag en memoire.
        ScheduledExecution reloaded = scheduledExecutionRepository.findById(id).orElseThrow();
        assertThat(reloaded.getNextRunAt()).isAfter(now);
    }

    @Test
    void poller_triggersADueRecurringSchedule_andReschedulesItInTheFuture() throws Exception {
        String scenarioId = createScenario("sched-poller");
        var request = new ScheduledExecutionRequest(UUID.fromString(scenarioId), "poller-schedule",
                ScheduleType.RECURRING_CRON, "0 0 8 * * *", null, "UTC");
        String response = mockMvc.perform(post("/api/scheduled-executions").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(response).get("id").asText());

        ScheduledExecution schedule = scheduledExecutionRepository.findById(id).orElseThrow();
        schedule.setNextRunAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        scheduledExecutionRepository.saveAndFlush(schedule);

        poller.pollAndTrigger();

        long deadline = System.currentTimeMillis() + 5000;
        ScheduledExecution reloaded;
        while (true) {
            reloaded = scheduledExecutionRepository.findById(id).orElseThrow();
            if (reloaded.getLastExecutionId() != null || System.currentTimeMillis() > deadline) break;
            Thread.sleep(20);
        }
        assertThat(reloaded.getLastExecutionId()).as("Le poller doit avoir reellement declenche une Execution").isNotNull();
        assertThat(reloaded.getNextRunAt()).as("Une RECURRING_CRON doit toujours etre reprogrammee dans le futur apres declenchement").isAfter(Instant.now());
        waitForExecutionSuccess(reloaded.getLastExecutionId().toString());
    }
}
