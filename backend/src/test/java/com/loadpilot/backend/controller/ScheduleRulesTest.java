package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.dto.request.ApplicationRequest;
import com.loadpilot.backend.dto.request.ScenarioRequest;
import com.loadpilot.backend.dto.request.ScheduledExecutionRequest;
import com.loadpilot.backend.dto.request.StepRequest;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.enums.HttpMethod;
import com.loadpilot.backend.enums.ScheduleNotifyMode;
import com.loadpilot.backend.enums.ScheduleType;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.ScenarioRepository;
import com.loadpilot.backend.repository.ScheduledExecutionRepository;
import com.loadpilot.backend.service.impl.ScheduledExecutionPoller;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Regles de la planification, de bout en bout : apercu des prochains lancements (fuseau, CRON a 5 champs,
 * date de fin, validation), etats calcules (Active, En pause, Suspendue, Terminee, Expiree), lancement
 * ignore (execution precedente en cours), lancements manques (jamais rattrapes), historique, notifications
 * de fin selon le reglage, et droits (VIEWER consulte seulement).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScheduleRulesTest {

    private static HttpServer targetServer;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ScheduledExecutionRepository scheduleRepository;
    @Autowired
    private ExecutionRepository executionRepository;
    @Autowired
    private ScenarioRepository scenarioRepository;
    @Autowired
    private ScheduledExecutionPoller poller;
    @MockBean
    private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_ENGINEER =
            jwt().jwt(j -> j.subject("rules-engineer")).authorities(new SimpleGrantedAuthority("ROLE_PERFORMANCE_ENGINEER"));
    private static final RequestPostProcessor AS_VIEWER =
            jwt().jwt(j -> j.subject("rules-viewer")).authorities(new SimpleGrantedAuthority("ROLE_VIEWER"));

    @BeforeAll
    static void startTargetServer() throws IOException {
        targetServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        targetServer.createContext("/ok", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        targetServer.createContext("/ko", exchange -> {
            exchange.sendResponseHeaders(500, -1);
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

    // ------------------------------------------------------------------ helpers

    private ResultActions send(MockHttpServletRequestBuilder request, RequestPostProcessor as, Object body) throws Exception {
        request.with(as);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        return mockMvc.perform(request);
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String createScenario(String prefix, String path) throws Exception {
        return createScenarioAt(prefix, "http://localhost:" + targetServer.getAddress().getPort(), path);
    }

    private String createScenarioAt(String prefix, String baseUrl, String path) throws Exception {
        String appId = json(send(post("/api/applications"), AS_ENGINEER, new ApplicationRequest(prefix + "-app", "d", baseUrl))
                .andExpect(status().isCreated())).get("id").asText();
        String scenarioId = json(send(post("/api/scenarios"), AS_ENGINEER,
                new ScenarioRequest(UUID.fromString(appId), prefix + "-scenario", "d")).andExpect(status().isCreated())).get("id").asText();
        send(post("/api/steps"), AS_ENGINEER, new StepRequest(UUID.fromString(scenarioId), prefix + "-step", HttpMethod.GET, path,
                null, null, 1, 200, null, null, null, null, null, null)).andExpect(status().isCreated());
        return scenarioId;
    }

    private Map<String, Object> schedule(String scenarioId, String name, String type, String cron, Instant runAt, String tz,
                                         Instant endAt, String notifyMode) {
        Map<String, Object> body = new HashMap<>();
        body.put("scenarioId", scenarioId);
        body.put("name", name);
        body.put("scheduleType", type);
        body.put("cronExpression", cron);
        body.put("runAt", runAt == null ? null : runAt.toString());
        body.put("timezone", tz);
        body.put("endAt", endAt == null ? null : endAt.toString());
        body.put("notifyMode", notifyMode);
        return body;
    }

    private String create(Map<String, Object> body) throws Exception {
        return json(send(post("/api/scheduled-executions"), AS_ENGINEER, body).andExpect(status().isCreated())).get("id").asText();
    }

    private JsonNode get(String scheduleId) throws Exception {
        return json(send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/scheduled-executions/" + scheduleId),
                AS_VIEWER, null).andExpect(status().isOk()));
    }

    private JsonNode history(String scheduleId) throws Exception {
        return json(send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/scheduled-executions/" + scheduleId + "/history"), AS_VIEWER, null).andExpect(status().isOk())).get("items");
    }

    private void forceDue(String scheduleId, Instant at) {
        var s = scheduleRepository.findById(UUID.fromString(scheduleId)).orElseThrow();
        s.setNextRunAt(at);
        scheduleRepository.saveAndFlush(s);
    }

    private List<JsonNode> notifications(String scheduleId) throws Exception {
        JsonNode content = json(send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/notifications?size=100"),
                AS_ENGINEER, null).andExpect(status().isOk())).get("content");
        List<JsonNode> out = new ArrayList<>();
        content.forEach(n -> {
            if (scheduleId.equals(n.get("relatedScheduleId").asText())) out.add(n);
        });
        return out;
    }

    private JsonNode waitForFinishedRun(String scheduleId) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode items = history(scheduleId);
            for (JsonNode item : items) {
                if ("EXECUTION".equals(item.get("kind").asText())
                        && List.of("SUCCESS", "THRESHOLDS_EXCEEDED", "FAILED").contains(item.get("status").asText())
                        && !item.get("p95Ms").isNull()) return item;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Aucune execution planifiee terminee a temps.");
    }

    private List<JsonNode> waitForNotifications(String scheduleId, int count) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        List<JsonNode> list = notifications(scheduleId);
        while (list.size() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            list = notifications(scheduleId);
        }
        return list;
    }

    // ------------------------------------------------------------------ apercu et validation

    @Test
    void preview_frequenciesTimezoneCustomCronAndValidation() throws Exception {
        // Chaque jour a 02:00, heure de Paris (CRON standard a 5 champs) : 3 dates, a 02:00 locales.
        JsonNode daily = json(send(post("/api/scheduled-executions/preview"), AS_VIEWER,
                Map.of("scheduleType", "RECURRING_CRON", "cronExpression", "0 2 * * *", "timezone", "Europe/Paris"))
                .andExpect(status().isOk()));
        assertThat(daily.get("cronExpression").asText()).isEqualTo("0 0 2 * * *");
        assertThat(daily.get("nextRuns")).hasSize(3);
        for (JsonNode run : daily.get("nextRuns")) {
            assertThat(Instant.parse(run.asText()).atZone(java.time.ZoneId.of("Europe/Paris")).getHour()).isEqualTo(2);
        }
        // Jours ouvres : jamais un samedi ni un dimanche.
        JsonNode weekdays = json(send(post("/api/scheduled-executions/preview"), AS_VIEWER,
                Map.of("scheduleType", "RECURRING_CRON", "cronExpression", "0 30 6 * * MON-FRI", "timezone", "Africa/Casablanca"))
                .andExpect(status().isOk()));
        for (JsonNode run : weekdays.get("nextRuns")) {
            int day = Instant.parse(run.asText()).atZone(java.time.ZoneId.of("Africa/Casablanca")).getDayOfWeek().getValue();
            assertThat(day).isBetween(1, 5);
        }
        // Date de fin : l'apercu s'arrete a la fin.
        Instant endAt = Instant.now().plus(36, ChronoUnit.HOURS);
        JsonNode bounded = json(send(post("/api/scheduled-executions/preview"), AS_VIEWER,
                Map.of("scheduleType", "RECURRING_CRON", "cronExpression", "0 0 * * *", "timezone", "UTC", "endAt", endAt.toString()))
                .andExpect(status().isOk()));
        assertThat(bounded.get("nextRuns").size()).isBetween(1, 2);

        send(post("/api/scheduled-executions/preview"), AS_VIEWER,
                Map.of("scheduleType", "RECURRING_CRON", "cronExpression", "toutes les nuits", "timezone", "UTC"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Expression CRON invalide")));
        send(post("/api/scheduled-executions/preview"), AS_VIEWER,
                Map.of("scheduleType", "ONE_TIME", "runAt", Instant.now().minusSeconds(60).toString(), "timezone", "UTC"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("déjà passées")));
        send(post("/api/scheduled-executions/preview"), AS_VIEWER,
                Map.of("scheduleType", "RECURRING_CRON", "cronExpression", "0 2 * * *", "timezone", "Lune/Base"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ etats et droits

    @Test
    void states_activePausedSuspendedExpired_andViewerIsReadOnly() throws Exception {
        String scenarioId = createScenario("rules-states-" + UUID.randomUUID(), "/ok");
        String id = create(schedule(scenarioId, "Test nocturne", "RECURRING_CRON", "0 2 * * *", null, "Africa/Casablanca",
                Instant.now().plus(30, ChronoUnit.DAYS), "PROBLEMS"));
        JsonNode created = get(id);
        assertThat(created.get("state").asText()).isEqualTo("ACTIVE");
        assertThat(created.get("cronExpression").asText()).isEqualTo("0 0 2 * * *");
        assertThat(created.get("notifyMode").asText()).isEqualTo("PROBLEMS");
        assertThat(created.get("endAt").isNull()).isFalse();

        // VIEWER : consultation seulement.
        send(post("/api/scheduled-executions"), AS_VIEWER,
                schedule(scenarioId, "x", "RECURRING_CRON", "0 2 * * *", null, "UTC", null, null)).andExpect(status().isForbidden());
        send(post("/api/scheduled-executions/" + id + "/run-now"), AS_VIEWER, null).andExpect(status().isForbidden());
        send(patch("/api/scheduled-executions/" + id + "/disable"), AS_VIEWER, null).andExpect(status().isForbidden());
        send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/scheduled-executions/" + id), AS_VIEWER, null)
                .andExpect(status().isForbidden());

        send(patch("/api/scheduled-executions/" + id + "/disable"), AS_ENGINEER, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PAUSED"));
        send(patch("/api/scheduled-executions/" + id + "/enable"), AS_ENGINEER, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ACTIVE"));

        // Scenario desactive : Suspendue (raison au survol), puis reprise automatique a la reactivation.
        send(post("/api/scenarios/" + scenarioId + "/deactivate"), AS_ENGINEER, null).andExpect(status().isOk());
        JsonNode suspended = get(id);
        assertThat(suspended.get("state").asText()).isEqualTo("SUSPENDED");
        assertThat(suspended.get("stateReason").asText()).startsWith("Scénario désactivé");
        send(post("/api/scenarios/" + scenarioId + "/activate"), AS_ENGINEER, null).andExpect(status().isOk());
        assertThat(get(id).get("state").asText()).isEqualTo("ACTIVE");

        // Scenario archive : Suspendue, puis reprise a la restauration.
        send(post("/api/scenarios/" + scenarioId + "/archive"), AS_ENGINEER, null).andExpect(status().isOk());
        JsonNode archived = get(id);
        assertThat(archived.get("state").asText()).isEqualTo("SUSPENDED");
        assertThat(archived.get("stateReason").asText()).startsWith("Scénario archivé");
        send(post("/api/scenarios/" + scenarioId + "/restore"), AS_ENGINEER, null).andExpect(status().isOk());
        assertThat(get(id).get("state").asText()).isEqualTo("ACTIVE");

        // Date de fin depassee : Expiree.
        var s = scheduleRepository.findById(UUID.fromString(id)).orElseThrow();
        s.setEndAt(Instant.now().minusSeconds(60));
        s.setNextRunAt(null);
        scheduleRepository.saveAndFlush(s);
        assertThat(get(id).get("state").asText()).isEqualTo("EXPIRED");

        // Modification : nouvelle frequence, recalcul, de nouveau Active.
        send(put("/api/scheduled-executions/" + id), AS_ENGINEER,
                schedule(scenarioId, "Test nocturne", "RECURRING_CRON", "0 0 3 * * MON-FRI", null, "Africa/Casablanca", null, "ALWAYS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.endAt").isEmpty());
    }

    // ------------------------------------------------------------------ ignoree / manquee

    @Test
    void automaticRun_skippedWhilePreviousExecutionStillRunning() throws Exception {
        String scenarioId = createScenario("rules-running-" + UUID.randomUUID(), "/ok");
        String id = create(schedule(scenarioId, "Test chevauchement", "RECURRING_CRON", "*/5 * * * *", null, "UTC", null, null));
        Execution running = executionRepository.saveAndFlush(Execution.builder()
                .scenario(scenarioRepository.findById(UUID.fromString(scenarioId)).orElseThrow())
                .status(ExecutionStatus.RUNNING).startedAt(Instant.now()).virtualUsers(1).totalSteps(1)
                .successfulSteps(0).failedSteps(0).build());
        try {
            // Manuel : refuse clairement.
            send(post("/api/scheduled-executions/" + id + "/run-now"), AS_ENGINEER, null)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Une exécution de ce scénario est encore en cours")));

            // Automatique : rien n'est lance, l'historique et la notification expliquent pourquoi.
            forceDue(id, Instant.now().minusSeconds(5));
            long before = executionRepository.count();
            poller.pollAndTrigger();
            assertThat(executionRepository.count()).isEqualTo(before);
            JsonNode items = history(id);
            assertThat(items).hasSize(1);
            assertThat(items.get(0).get("outcome").asText()).isEqualTo("SKIPPED_RUNNING");
            assertThat(items.get(0).get("reason").asText()).isEqualTo("Ignorée : exécution précédente encore en cours.");
            JsonNode view = get(id);
            assertThat(view.get("lastEvent").get("outcome").asText()).isEqualTo("SKIPPED_RUNNING");
            assertThat(Instant.parse(view.get("nextRunAt").asText())).isAfter(Instant.now());
            assertThat(notifications(id)).anyMatch(n -> n.get("title").asText()
                    .equals("Test chevauchement : ignoré · exécution précédente encore en cours"));
        } finally {
            running.setStatus(ExecutionStatus.CANCELLED);
            executionRepository.saveAndFlush(running);
        }
    }

    @Test
    void missedRuns_whileBackendWasDown_areRecordedNotCaughtUp() throws Exception {
        String scenarioId = createScenario("rules-missed-" + UUID.randomUUID(), "/ok");
        String id = create(schedule(scenarioId, "Test manque", "RECURRING_CRON", "*/5 * * * *", null, "UTC", null, null));
        // Prevu il y a une heure (backend arrete depuis) : 12 a 13 occurrences manquees.
        forceDue(id, Instant.now().minus(1, ChronoUnit.HOURS));
        long before = executionRepository.count();

        poller.pollAndTrigger();

        assertThat(executionRepository.count()).as("aucun rattrapage").isEqualTo(before);
        JsonNode items = history(id);
        assertThat(items.size()).isBetween(12, 13);
        items.forEach(i -> {
            assertThat(i.get("outcome").asText()).isEqualTo("MISSED");
            assertThat(i.get("reason").asText()).startsWith("Manquée");
        });
        Instant next = Instant.parse(get(id).get("nextRunAt").asText());
        assertThat(next).isAfter(Instant.now()).isBefore(Instant.now().plus(6, ChronoUnit.MINUTES));
        assertThat(notifications(id)).anyMatch(n -> n.get("title").asText().matches("Test manque : 1[23] lancements manqués"));

        // Un lancement en retard de quelques secondes (poller occupe) est lance normalement.
        String oneTime = create(schedule(scenarioId, "Unique", "ONE_TIME", null, Instant.now().plusSeconds(3600), "UTC", null, null));
        forceDue(oneTime, Instant.now().minusSeconds(10));
        poller.pollAndTrigger();
        JsonNode run = waitForFinishedRun(oneTime);
        assertThat(run.get("status").asText()).isEqualTo("SUCCESS");
        JsonNode done = get(oneTime);
        assertThat(done.get("state").asText()).isEqualTo("COMPLETED");
        assertThat(done.get("nextRunAt").isNull()).isTrue();
    }

    // ------------------------------------------------------------------ fin d'execution : notifications et historique

    @Test
    void finishedRuns_notifySuccessOrFailure_withReportLink_andFillHistory() throws Exception {
        String okScenario = createScenario("rules-notify-ok-" + UUID.randomUUID(), "/ok");
        String always = create(schedule(okScenario, "Nocturne OK", "RECURRING_CRON", "0 2 * * *", null, "UTC", null, "ALWAYS"));
        send(post("/api/scheduled-executions/" + always + "/run-now"), AS_ENGINEER, null).andExpect(status().isAccepted());
        JsonNode run = waitForFinishedRun(always);
        List<JsonNode> okNotifications = waitForNotifications(always, 1);
        assertThat(okNotifications).hasSize(1);
        JsonNode success = okNotifications.get(0);
        assertThat(success.get("type").asText()).isEqualTo("EXECUTION_SUCCESS");
        assertThat(success.get("title").asText()).isEqualTo("Nocturne OK : réussi · P95 " + run.get("p95Ms").asLong() + " ms");
        assertThat(success.get("relatedExecutionId").asText()).isEqualTo(run.get("executionId").asText());

        JsonNode view = get(always);
        assertThat(view.get("lastRun").get("executionId").asText()).isEqualTo(run.get("executionId").asText());
        assertThat(view.get("lastRun").get("p95Ms").asLong()).isEqualTo(run.get("p95Ms").asLong());
        assertThat(run.get("errorRatePercent").asDouble()).isZero();
        assertThat(run.get("totalRequests").asLong()).isEqualTo(1);

        // Reglage « seulement en cas d'echec ou de regression » : pas de notification de reussite.
        String problemsOnly = create(schedule(okScenario, "Nocturne discret", "RECURRING_CRON", "0 2 * * *", null, "UTC", null,
                ScheduleNotifyMode.PROBLEMS.name()));
        send(post("/api/scheduled-executions/" + problemsOnly + "/run-now"), AS_ENGINEER, null).andExpect(status().isAccepted());
        waitForFinishedRun(problemsOnly);
        Thread.sleep(500);
        assertThat(notifications(problemsOnly)).isEmpty();

        // Seuil depasse (l'application repond en erreur) : notifie meme en mode PROBLEMS, avec le taux d'erreurs.
        String koScenario = createScenario("rules-notify-ko-" + UUID.randomUUID(), "/ko");
        String failing = create(schedule(koScenario, "Nocturne KO", "RECURRING_CRON", "0 2 * * *", null, "UTC", null, "PROBLEMS"));
        send(post("/api/scheduled-executions/" + failing + "/run-now"), AS_ENGINEER, null).andExpect(status().isAccepted());
        JsonNode failedRun = waitForFinishedRun(failing);
        assertThat(failedRun.get("status").asText()).isEqualTo("THRESHOLDS_EXCEEDED");
        List<JsonNode> koNotifications = waitForNotifications(failing, 1);
        assertThat(koNotifications).hasSize(1);
        assertThat(koNotifications.get(0).get("type").asText()).isEqualTo("EXECUTION_THRESHOLDS_EXCEEDED");
        assertThat(koNotifications.get(0).get("title").asText()).isEqualTo("Nocturne KO : seuils dépassés · 100 % d'erreurs");
        assertThat(koNotifications.get(0).get("relatedExecutionId").asText()).isEqualTo(failedRun.get("executionId").asText());

        // Echec reel (application injoignable : aucune reponse) : « échoué ».
        String downScenario = createScenarioAt("rules-notify-down-" + UUID.randomUUID(), "http://localhost:1", "/ok");
        String down = create(schedule(downScenario, "Nocturne injoignable", "RECURRING_CRON", "0 2 * * *", null, "UTC", null, "PROBLEMS"));
        send(post("/api/scheduled-executions/" + down + "/run-now"), AS_ENGINEER, null).andExpect(status().isAccepted());
        JsonNode downRun = waitForFinishedRun(down);
        assertThat(downRun.get("status").asText()).isEqualTo("FAILED");
        List<JsonNode> downNotifications = waitForNotifications(down, 1);
        assertThat(downNotifications.get(0).get("type").asText()).isEqualTo("EXECUTION_FAILED");
        assertThat(downNotifications.get(0).get("title").asText()).isEqualTo("Nocturne injoignable : échoué · 100 % d'erreurs");
    }
}
