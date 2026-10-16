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
import com.loadpilot.backend.dto.request.ScheduledExecutionRequest;
import com.loadpilot.backend.entity.AuditLog;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.enums.ScheduleType;
import com.loadpilot.backend.repository.AuditLogRepository;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.ScenarioRepository;
import com.loadpilot.backend.repository.ScheduledExecutionRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
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

/** Statut INACTIVE (activation, planifications suspendues), actions groupees et pagination des scenarios. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScenarioStatusAndBulkControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ScheduledExecutionRepository scheduleRepository;
    @Autowired
    private ScenarioRepository scenarioRepository;
    @Autowired
    private ExecutionRepository executionRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @MockBean
    private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_SUPER_ADMIN = jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
    private static final RequestPostProcessor AS_ENGINEER = jwt().authorities(new SimpleGrantedAuthority("ROLE_PERFORMANCE_ENGINEER"));
    private static final RequestPostProcessor AS_VIEWER = jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"));

    private ResultActions send(MockHttpServletRequestBuilder builder, RequestPostProcessor as, Object body) throws Exception {
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        return mockMvc.perform(builder.with(as));
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String createApplication(String name) throws Exception {
        return json(send(post("/api/applications"), AS_SUPER_ADMIN, Map.of("name", name, "url", "http://localhost:9"))
                .andExpect(status().isCreated())).get("id").asText();
    }

    /** Scenario ACTIVE avec une etape (executable). */
    private String createScenario(String appId, String name, boolean draft) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("applicationId", appId);
        body.put("name", name);
        body.put("description", "Recherche-" + name);
        if (draft) body.put("draft", true);
        String id = json(send(post("/api/scenarios"), AS_ENGINEER, body).andExpect(status().isCreated())).get("id").asText();
        send(post("/api/steps"), AS_ENGINEER, Map.of("scenarioId", id, "name", "Sante", "method", "GET", "url", "/health", "order", 1))
                .andExpect(status().isCreated());
        return id;
    }

    private String createSchedule(String scenarioId, String name) throws Exception {
        var request = new ScheduledExecutionRequest(UUID.fromString(scenarioId), name, ScheduleType.ONE_TIME, null,
                Instant.now().plus(2, ChronoUnit.DAYS), "UTC");
        return json(send(post("/api/scheduled-executions"), AS_SUPER_ADMIN, request).andExpect(status().isCreated())).get("id").asText();
    }

    private Map<String, Object> bulk(Object ids, String status) {
        Map<String, Object> body = new HashMap<>();
        body.put("ids", ids);
        if (status != null) body.put("status", status);
        return body;
    }

    @Test
    void deactivate_pausesSchedules_refusesExecution_thenActivateAndResume() throws Exception {
        String appId = createApplication("status-app-1");
        String id = createScenario(appId, "A desactiver", false);
        String scheduleId = createSchedule(id, "nightly");

        send(post("/api/scenarios/" + id + "/deactivate"), AS_VIEWER, null).andExpect(status().isForbidden());
        send(post("/api/scenarios/" + id + "/deactivate"), AS_ENGINEER, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.pausedSchedules").value(1));
        var schedule = scheduleRepository.findById(UUID.fromString(scheduleId)).orElseThrow();
        assertThat(schedule.isEnabled()).isFalse();
        assertThat(schedule.isPausedByScenario()).isTrue();

        // Ignoree par le poller meme si on force "enabled" en base (filtre sur le statut ACTIVE).
        schedule.setEnabled(true);
        schedule.setNextRunAt(Instant.now().minusSeconds(60));
        scheduleRepository.saveAndFlush(schedule);
        assertThat(scheduleRepository.findDueScheduleIds(Instant.now())).doesNotContain(schedule.getId());
        schedule.setEnabled(false);
        schedule.setNextRunAt(schedule.getRunAt());
        scheduleRepository.saveAndFlush(schedule);

        send(post("/api/executions"), AS_ENGINEER, Map.of("scenarioId", id))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Le scénario est inactif, activez-le pour l'exécuter."));
        send(post("/api/scheduled-executions"), AS_SUPER_ADMIN, new ScheduledExecutionRequest(UUID.fromString(id), "x",
                ScheduleType.ONE_TIME, null, Instant.now().plus(1, ChronoUnit.DAYS), "UTC"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Le scénario est inactif, activez-le pour le planifier."));

        // Reste modifiable.
        send(put("/api/scenarios/" + id), AS_ENGINEER, Map.of("applicationId", appId, "name", "Renomme inactif"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INACTIVE"));

        // L'activation reprend aussitot les planifications suspendues (rien n'est rattrape).
        send(post("/api/scenarios/" + id + "/activate"), AS_ENGINEER, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.pausedSchedules").value(0));
        var resumed = scheduleRepository.findById(UUID.fromString(scheduleId)).orElseThrow();
        assertThat(resumed.isEnabled()).isTrue();
        assertThat(resumed.isPausedByScenario()).isFalse();
        assertThat(resumed.getNextRunAt()).isEqualTo(resumed.getRunAt());

        // La reprise explicite reste disponible et n'a plus rien a faire.
        send(post("/api/scenarios/" + id + "/resume-schedules"), AS_ENGINEER, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resumed").value(0))
                .andExpect(jsonPath("$.expired").value(0));

        List<AuditLog> audit = auditLogRepository.findAll();
        assertThat(audit).anyMatch(a -> a.getAction() == AuditAction.DISABLE && a.getModule() == AuditModule.SCENARIO
                && "A desactiver".equals(a.getTargetLabel())
                && a.getDescription().contains("Scénario désactivé — 1 planification(s) suspendue(s)"));
        assertThat(audit).anyMatch(a -> a.getAction() == AuditAction.ENABLE && a.getModule() == AuditModule.SCENARIO
                && "Renomme inactif".equals(a.getTargetLabel()) && a.getDescription().startsWith("Scénario réactivé"));
    }

    @Test
    void deactivate_refusedWhileExecutionRunning_andForDrafts() throws Exception {
        String appId = createApplication("status-app-2");
        String id = createScenario(appId, "En cours", false);
        executionRepository.saveAndFlush(Execution.builder()
                .scenario(scenarioRepository.findById(UUID.fromString(id)).orElseThrow())
                .startedAt(Instant.now()).status(ExecutionStatus.RUNNING)
                .totalSteps(1).successfulSteps(0).failedSteps(0).build());

        send(post("/api/scenarios/" + id + "/deactivate"), AS_ENGINEER, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("exécution est en cours")));

        String draftId = createScenario(appId, "Brouillon", true);
        send(post("/api/scenarios/" + draftId + "/deactivate"), AS_ENGINEER, null).andExpect(status().isBadRequest());

        // Groupe : succes partiels, raison par scenario.
        String other = createScenario(appId, "Libre", false);
        send(post("/api/scenarios/bulk-status"), AS_ENGINEER, bulk(List.of(id, draftId, other), "INACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded").value(1))
                .andExpect(jsonPath("$.failed").value(2))
                .andExpect(jsonPath("$.results[?(@.id == '" + other + "')].success").value(true))
                .andExpect(jsonPath("$.results[?(@.id == '" + id + "')].message").value(Matchers.hasItem(Matchers.containsString("exécution est en cours"))))
                .andExpect(jsonPath("$.results[?(@.id == '" + draftId + "')].message").value(Matchers.hasItem(Matchers.containsString("brouillon"))));
        send(post("/api/scenarios/bulk-status"), AS_ENGINEER, bulk(List.of(other), "ACTIVE"))
                .andExpect(jsonPath("$.succeeded").value(1));
        send(post("/api/scenarios/bulk-status"), AS_ENGINEER, bulk(List.of(other), "PAUSED")).andExpect(status().isBadRequest());
    }

    @Test
    void bulkArchiveRestoreDelete_withImpactAndRoles() throws Exception {
        String appId = createApplication("status-app-3");
        String a = createScenario(appId, "Bulk A", false);
        String b = createScenario(appId, "Bulk B", false);
        createSchedule(a, "s1");

        send(post("/api/scenarios/bulk-deletion-impact"), AS_ENGINEER, bulk(List.of(a, b), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenarios").value(2))
                .andExpect(jsonPath("$.steps").value(2))
                .andExpect(jsonPath("$.scheduledExecutions").value(1));

        send(post("/api/scenarios/bulk-archive"), AS_ENGINEER, bulk(List.of(a, b), null)).andExpect(jsonPath("$.succeeded").value(2));
        send(get("/api/scenarios?archived=true&page=0&size=10&search=Bulk"), AS_VIEWER, null)
                .andExpect(jsonPath("$.totalElements").value(2));
        send(post("/api/scenarios/bulk-restore"), AS_ENGINEER, bulk(List.of(a), null)).andExpect(jsonPath("$.succeeded").value(1));

        send(post("/api/scenarios/bulk-delete"), AS_ENGINEER, bulk(List.of(a, b), null)).andExpect(status().isForbidden());
        send(post("/api/scenarios/bulk-delete"), AS_SUPER_ADMIN, bulk(List.of(a, b, UUID.randomUUID().toString()), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded").value(2))
                .andExpect(jsonPath("$.failed").value(1));
        send(get("/api/scenarios/" + a), AS_VIEWER, null).andExpect(status().isNotFound());
        send(post("/api/scenarios/bulk-archive"), AS_ENGINEER, bulk(List.of(), null)).andExpect(status().isBadRequest());
    }

    @Test
    void page_filtersByStatusAndSearch_andSortsByDate() throws Exception {
        String appId = createApplication("page-app-unique");
        // Dates de creation toujours distinctes (sinon l'ordre de deux scenarios crees dans la meme
        // microseconde est indetermine et le tri par date ne peut pas etre verifie).
        String first = createScenario(appId, "Pagine 1", false);
        Thread.sleep(2);
        createScenario(appId, "Pagine 2", false);
        Thread.sleep(2);
        String third = createScenario(appId, "Pagine 3", false);
        send(post("/api/scenarios/" + third + "/deactivate"), AS_ENGINEER, null).andExpect(status().isOk());

        send(get("/api/scenarios?page=0&size=5&applicationId=" + appId + "&sort=createdAt,asc"), AS_VIEWER, null)
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].id").value(first));
        send(get("/api/scenarios?page=0&size=5&applicationId=" + appId + "&sort=createdAt,desc"), AS_VIEWER, null)
                .andExpect(jsonPath("$.content[0].id").value(third));
        send(get("/api/scenarios?page=0&size=5&applicationId=" + appId + "&status=INACTIVE"), AS_VIEWER, null)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].status").value("INACTIVE"));
        send(get("/api/scenarios?page=0&size=5&search=page-app-unique"), AS_VIEWER, null)
                .andExpect(jsonPath("$.totalElements").value(3));
        send(get("/api/scenarios?page=0&size=5&status=PAUSED"), AS_VIEWER, null).andExpect(status().isBadRequest());
        send(get("/api/scenarios?page=0&size=5&sort=password,asc"), AS_VIEWER, null).andExpect(status().isBadRequest());
    }

    @Test
    void manualReenable_ofScheduleOfInactiveScenario_isRefused() throws Exception {
        String appId = createApplication("status-app-5");
        String id = createScenario(appId, "Planifie", false);
        String scheduleId = createSchedule(id, "s");
        send(post("/api/scenarios/" + id + "/deactivate"), AS_ENGINEER, null).andExpect(status().isOk());

        send(patch("/api/scheduled-executions/" + scheduleId + "/enable"), AS_SUPER_ADMIN, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Le scénario est inactif, activez-le pour le planifier."));
    }
}
