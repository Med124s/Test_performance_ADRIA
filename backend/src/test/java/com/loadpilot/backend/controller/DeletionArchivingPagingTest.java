package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.entity.ExecutionStepResult;
import com.loadpilot.backend.entity.Metric;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.repository.ApplicationRepository;
import com.loadpilot.backend.repository.AuditLogRepository;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.ExecutionStepResultRepository;
import com.loadpilot.backend.repository.MetricRepository;
import com.loadpilot.backend.repository.ScenarioRepository;
import com.loadpilot.backend.repository.StepRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * Suppression coherente (migration 017) : etape supprimee sans perte
 * d'historique, impact, archivage/restauration, suppression definitive en
 * cascade, refus si execution en cours, actions groupees, pagination.
 * Historique d'execution insere directement en base (aucun appel HTTP reel).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeletionArchivingPagingTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private ScenarioRepository scenarioRepository;
    @Autowired private StepRepository stepRepository;
    @Autowired private ExecutionRepository executionRepository;
    @Autowired private ExecutionStepResultRepository resultRepository;
    @Autowired private MetricRepository metricRepository;
    @Autowired private AuditLogRepository auditLogRepository;

    @MockBean private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_SUPER_ADMIN = jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
    private static final RequestPostProcessor AS_ENGINEER = jwt().authorities(new SimpleGrantedAuthority("ROLE_PERFORMANCE_ENGINEER"));
    private static final RequestPostProcessor AS_VIEWER = jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"));

    // ------------------------------------------------------------------ helpers

    private JsonNode postJson(String url, Object body, RequestPostProcessor as) throws Exception {
        String response = mockMvc.perform(post(url).with(as).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return response.isBlank() ? null : objectMapper.readTree(response);
    }

    private String createApp(String name) throws Exception {
        return postJson("/api/applications", Map.of("name", name, "url", "http://example.com/" + UUID.randomUUID()), AS_SUPER_ADMIN)
                .get("id").asText();
    }

    private String createScenario(String appId, String name) throws Exception {
        return postJson("/api/scenarios", Map.of("applicationId", appId, "name", name), AS_SUPER_ADMIN).get("id").asText();
    }

    private String createStep(String scenarioId, String name, int order) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("scenarioId", scenarioId);
        body.put("name", name);
        body.put("method", "GET");
        body.put("url", "/" + name);
        body.put("order", order);
        return postJson("/api/steps", body, AS_SUPER_ADMIN).get("id").asText();
    }

    /** Insere une execution terminee (ou en cours) avec un resultat et une metrique par etape. */
    private Execution insertExecution(String scenarioId, ExecutionStatus status, String... stepIds) {
        var scenario = scenarioRepository.findById(UUID.fromString(scenarioId)).orElseThrow();
        Execution execution = executionRepository.saveAndFlush(Execution.builder()
                .scenario(scenario).startedAt(Instant.now()).status(status)
                .totalSteps(stepIds.length).successfulSteps(stepIds.length).failedSteps(0).build());
        for (String stepId : stepIds) {
            var step = stepRepository.findById(UUID.fromString(stepId)).orElseThrow();
            resultRepository.saveAndFlush(ExecutionStepResult.builder()
                    .execution(execution).step(step).stepName(step.getName()).stepMethod(step.getMethod()).stepUrl(step.getUrl())
                    .httpStatus(200).responseTime(12L).success(true).timestamp(Instant.now()).build());
            metricRepository.saveAndFlush(Metric.builder()
                    .application(scenario.getApplication()).scenario(scenario).step(step).execution(execution)
                    .responseTime(12).statusCode(200).timestamp(Instant.now()).build());
        }
        return execution;
    }

    private boolean audited(AuditAction action, AuditModule module, AuditResult result, String text) {
        return auditLogRepository.findAll().stream().anyMatch(a -> a.getAction() == action && a.getModule() == module
                && a.getResult() == result && a.getDescription() != null && a.getDescription().contains(text));
    }

    // ------------------------------------------------------------------ etapes

    @Test
    void deleteStep_withExecutionHistory_keepsHistoryReadable_andRenumbersRemainingSteps() throws Exception {
        String appId = createApp("step-history-app");
        String scenarioId = createScenario(appId, "step-history");
        String first = createStep(scenarioId, "login", 1);
        String second = createStep(scenarioId, "balance", 2);
        String third = createStep(scenarioId, "logout", 3);
        Execution execution = insertExecution(scenarioId, ExecutionStatus.SUCCESS, first, second, third);

        mockMvc.perform(delete("/api/steps/" + second).with(AS_ENGINEER)).andExpect(status().isNoContent());

        // Etapes restantes renumerotees 1..n.
        mockMvc.perform(get("/api/steps/" + third).with(AS_VIEWER)).andExpect(jsonPath("$.order").value(2));
        // Historique intact : le resultat de l'etape supprimee reste lisible via sa copie.
        mockMvc.perform(get("/api/executions/" + execution.getId()).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[*].stepName").value(hasItem("balance")));
        mockMvc.perform(get("/api/executions/" + execution.getId() + "/report").with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[*].stepName").value(hasItem("balance")));
        assertThat(resultRepository.findAll().stream().filter(r -> "balance".equals(r.getStepName())))
                .allMatch(r -> r.getStep() == null);
    }

    @Test
    void deleteStep_whileExecutionRunning_isRefusedWithClearMessage() throws Exception {
        String scenarioId = createScenario(createApp("step-running-app"), "step-running");
        String stepId = createStep(scenarioId, "only", 1);
        insertExecution(scenarioId, ExecutionStatus.RUNNING);

        mockMvc.perform(delete("/api/steps/" + stepId).with(AS_SUPER_ADMIN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Une exécution est en cours, arrêtez-la avant de supprimer."));
    }

    // ------------------------------------------------------------------ impact, archivage, suppression definitive

    @Test
    void application_impact_archive_restore_and_permanentCascade() throws Exception {
        String name = "cascade-" + UUID.randomUUID();
        String appId = createApp(name);
        String scenarioId = createScenario(appId, "cascade-scenario");
        String stepId = createStep(scenarioId, "s1", 1);
        insertExecution(scenarioId, ExecutionStatus.SUCCESS, stepId);

        mockMvc.perform(get("/api/applications/" + appId + "/deletion-impact").with(AS_ENGINEER))
                .andExpect(jsonPath("$.scenarios").value(1))
                .andExpect(jsonPath("$.steps").value(1))
                .andExpect(jsonPath("$.executions").value(1))
                .andExpect(jsonPath("$.hasRunningExecution").value(false));

        // Archivage : masquee des listes (et ses scenarios), visible avec archived=true, non executable.
        mockMvc.perform(post("/api/applications/" + appId + "/archive").with(AS_ENGINEER))
                .andExpect(jsonPath("$.archived").value(true));
        mockMvc.perform(get("/api/applications").with(AS_VIEWER)).andExpect(jsonPath("$[*].id").value(not(hasItem(appId))));
        mockMvc.perform(get("/api/applications?archived=true").with(AS_VIEWER)).andExpect(jsonPath("$[*].id").value(hasItem(appId)));
        mockMvc.perform(get("/api/scenarios").with(AS_VIEWER)).andExpect(jsonPath("$[*].id").value(not(hasItem(scenarioId))));
        mockMvc.perform(post("/api/executions").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenarioId\":\"" + scenarioId + "\"}"))
                .andExpect(status().isConflict());
        assertThat(audited(AuditAction.ARCHIVE, AuditModule.APPLICATION, AuditResult.SUCCESS, name)).isTrue();

        mockMvc.perform(post("/api/applications/" + appId + "/restore").with(AS_ENGINEER))
                .andExpect(jsonPath("$.archived").value(false));
        mockMvc.perform(get("/api/scenarios").with(AS_VIEWER)).andExpect(jsonPath("$[*].id").value(hasItem(scenarioId)));

        // Suppression definitive : reservee a SUPER_ADMIN, cascade complete.
        mockMvc.perform(delete("/api/applications/" + appId).with(AS_ENGINEER)).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/applications/" + appId).with(AS_SUPER_ADMIN)).andExpect(status().isNoContent());
        assertThat(applicationRepository.findById(UUID.fromString(appId))).isEmpty();
        assertThat(scenarioRepository.findById(UUID.fromString(scenarioId))).isEmpty();
        assertThat(stepRepository.findById(UUID.fromString(stepId))).isEmpty();
        assertThat(executionRepository.findByScenarioId(UUID.fromString(scenarioId), org.springframework.data.domain.Sort.unsorted())).isEmpty();
        assertThat(audited(AuditAction.DELETE, AuditModule.APPLICATION, AuditResult.SUCCESS, name)).isTrue();
    }

    @Test
    void runningExecution_blocksArchiveAndPermanentDelete() throws Exception {
        String appId = createApp("running-app");
        String scenarioId = createScenario(appId, "running-scenario");
        insertExecution(scenarioId, ExecutionStatus.RUNNING);

        mockMvc.perform(get("/api/applications/" + appId + "/deletion-impact").with(AS_ENGINEER))
                .andExpect(jsonPath("$.hasRunningExecution").value(true));
        mockMvc.perform(delete("/api/applications/" + appId).with(AS_SUPER_ADMIN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Une exécution est en cours, arrêtez-la avant de supprimer."));
        mockMvc.perform(delete("/api/scenarios/" + scenarioId).with(AS_SUPER_ADMIN)).andExpect(status().isConflict());
        mockMvc.perform(post("/api/scenarios/" + scenarioId + "/archive").with(AS_SUPER_ADMIN)).andExpect(status().isConflict());
    }

    @Test
    void scenario_archiveRestore_and_restoreRefusedWhileApplicationArchived() throws Exception {
        String appId = createApp("scenario-archive-app");
        String scenarioId = createScenario(appId, "to-archive");

        mockMvc.perform(post("/api/scenarios/" + scenarioId + "/archive").with(AS_ENGINEER)).andExpect(jsonPath("$.archived").value(true));
        mockMvc.perform(get("/api/scenarios?archived=true").with(AS_VIEWER)).andExpect(jsonPath("$[*].id").value(hasItem(scenarioId)));
        mockMvc.perform(post("/api/applications/" + appId + "/archive").with(AS_ENGINEER));
        mockMvc.perform(post("/api/scenarios/" + scenarioId + "/restore").with(AS_ENGINEER))
                .andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------ actions groupees

    @Test
    void bulkArchive_reportsPartialSuccess_andPermanentBulkDeleteRequiresSuperAdmin() throws Exception {
        String ok = createApp("bulk-ok");
        String busy = createApp("bulk-busy");
        insertExecution(createScenario(busy, "busy-scenario"), ExecutionStatus.RUNNING);

        JsonNode impact = postJson("/api/applications/bulk-deletion-impact", Map.of("ids", List.of(ok, busy)), AS_ENGINEER);
        assertThat(impact.get("applications").asLong()).isEqualTo(2);
        assertThat(impact.get("hasRunningExecution").asBoolean()).isTrue();

        JsonNode result = postJson("/api/applications/bulk-archive", Map.of("ids", List.of(ok, busy), "mode", "ARCHIVE"), AS_ENGINEER);
        assertThat(result.get("succeeded").asInt()).isEqualTo(1);
        assertThat(result.get("failed").asInt()).isEqualTo(1);
        assertThat(result.toString()).contains("Une exécution est en cours");

        mockMvc.perform(post("/api/applications/bulk-delete").with(AS_ENGINEER).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("ids", List.of(ok), "mode", "PERMANENT"))))
                .andExpect(status().isForbidden());
        JsonNode deleted = postJson("/api/applications/bulk-delete", Map.of("ids", List.of(ok), "mode", "PERMANENT"), AS_SUPER_ADMIN);
        assertThat(deleted.get("succeeded").asInt()).isEqualTo(1);
        assertThat(applicationRepository.findById(UUID.fromString(ok))).isEmpty();
    }

    // ------------------------------------------------------------------ pagination

    @Test
    void paging_searchSortClampAndValidation() throws Exception {
        String tag = "paging-" + UUID.randomUUID().toString().substring(0, 8);
        for (String n : List.of("b", "a", "c", "d", "e", "f")) createApp(tag + "-" + n);

        mockMvc.perform(get("/api/applications?page=0&size=5&sort=name,asc&search=" + tag).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.content[0].name").value(tag + "-a"))
                .andExpect(jsonPath("$.content.length()").value(5));
        mockMvc.perform(get("/api/applications?page=1&size=5&sort=name,desc&search=" + tag).with(AS_VIEWER))
                .andExpect(jsonPath("$.content[0].name").value(tag + "-a"));
        // size borne a 5..100
        mockMvc.perform(get("/api/applications?page=0&size=1000&search=" + tag).with(AS_VIEWER)).andExpect(jsonPath("$.size").value(100));
        mockMvc.perform(get("/api/applications?page=0&size=1&search=" + tag).with(AS_VIEWER)).andExpect(jsonPath("$.size").value(5));
        // tri limite aux champs autorises, filtre de status
        mockMvc.perform(get("/api/applications?page=0&sort=authConfig,asc").with(AS_VIEWER)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/applications?page=0&status=UNTESTED&search=" + tag).with(AS_VIEWER))
                .andExpect(jsonPath("$.totalElements").value(6));
        mockMvc.perform(get("/api/applications?page=0&status=CONNECTED&search=" + tag).with(AS_VIEWER))
                .andExpect(jsonPath("$.totalElements").value(0));
    }
}
