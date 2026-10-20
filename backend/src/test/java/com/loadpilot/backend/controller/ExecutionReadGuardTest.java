package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.entity.ExecutionStepResult;
import com.loadpilot.backend.entity.Metric;
import com.loadpilot.backend.entity.Scenario;
import com.loadpilot.backend.entity.Step;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.enums.HttpMethod;
import com.loadpilot.backend.enums.ScenarioStatus;
import com.loadpilot.backend.enums.StepStatus;
import com.loadpilot.backend.repository.AppUserRepository;
import com.loadpilot.backend.repository.ApplicationRepository;
import com.loadpilot.backend.repository.AuditLogRepository;
import com.loadpilot.backend.repository.ExecutionReportSnapshotRepository;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.ExecutionStepResultRepository;
import com.loadpilot.backend.repository.MetricRepository;
import com.loadpilot.backend.repository.ScenarioRepository;
import com.loadpilot.backend.repository.StepRepository;
import com.loadpilot.backend.service.impl.ReportSnapshotService;
import com.loadpilot.backend.service.impl.ResultsMemoryBudget;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Garde-fou des lectures completes, de bout en bout, avec un budget reduit a 1 000 « resultats » :
 * chaque lecture est refusee proprement (413, message clair) au-dela de sa propre limite et servie
 * en deca ; le rattrapage des rapports figes saute une execution trop volumineuse (journal d'audit)
 * sans boucle d'erreur.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "app.execution.max-results-in-memory=1000")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExecutionReadGuardTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AppUserRepository appUserRepository;
    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private ScenarioRepository scenarioRepository;
    @Autowired private StepRepository stepRepository;
    @Autowired private ExecutionRepository executionRepository;
    @Autowired private ExecutionStepResultRepository resultRepository;
    @Autowired private MetricRepository metricRepository;
    @Autowired private ExecutionReportSnapshotRepository snapshotRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private ReportSnapshotService reportSnapshotService;
    @Autowired private ResultsMemoryBudget budget;
    @MockBean private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_VIEWER = jwt().jwt(j -> j.subject("read-guard-viewer"))
            .authorities(new SimpleGrantedAuthority("ROLE_PERFORMANCE_ENGINEER"));

    private Application app;
    private Scenario scenario;
    private Step step;

    @BeforeEach
    void data() {
        AppUser user = appUserRepository.saveAndFlush(AppUser.builder().keycloakSubject(UUID.randomUUID().toString())
                .username("read-guard-" + UUID.randomUUID()).enabled(true).build());
        app = applicationRepository.saveAndFlush(Application.builder().name("lecture-" + UUID.randomUUID())
                .url("http://localhost:1").createdBy(user).build());
        scenario = scenarioRepository.saveAndFlush(Scenario.builder().application(app).name("lecture-" + UUID.randomUUID())
                .status(ScenarioStatus.ACTIVE).createdBy(user).virtualUsers(1).rampUpSeconds(0).thinkTimeMs(0).build());
        step = stepRepository.saveAndFlush(Step.builder().scenario(scenario).name("Appel").method(HttpMethod.GET)
                .url("/ok").order(1).status(StepStatus.ACTIVE).build());
    }

    /** Execution terminee avec "count" resultats en base (et autant de Metric si demande). */
    private Execution finished(int count, boolean withMetrics) {
        Instant start = Instant.parse("2026-10-16T08:00:00Z");
        Execution execution = executionRepository.saveAndFlush(Execution.builder().scenario(scenario).startedAt(start)
                .finishedAt(start.plusSeconds(60)).duration(60_000L).status(ExecutionStatus.SUCCESS).virtualUsers(1)
                .rampUpSeconds(0).totalSteps(1).successfulSteps(count).failedSteps(0).maxErrorRatePercent(1.0).build());
        List<ExecutionStepResult> results = new ArrayList<>();
        List<Metric> metrics = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            results.add(ExecutionStepResult.builder().execution(execution).step(step).stepName("Appel").stepMethod(HttpMethod.GET)
                    .stepUrl("/ok").httpStatus(200).responseTime(10L + i % 50).success(true).timestamp(start.plusMillis(i * 10L))
                    .virtualUser(0).iteration(i).requestUrl("http://localhost:1/ok").build());
            if (withMetrics) {
                metrics.add(Metric.builder().application(app).scenario(scenario).step(step).execution(execution)
                        .responseTime(10).statusCode(200).timestamp(start.plusMillis(i * 10L)).build());
            }
        }
        resultRepository.saveAll(results);
        metricRepository.saveAll(metrics);
        return execution;
    }

    private MockHttpServletResponse call(String url) throws Exception {
        return mockMvc.perform(get(url).with(AS_VIEWER)).andReturn().getResponse();
    }

    private static String message(MockHttpServletResponse response) throws Exception {
        JsonNode body = new ObjectMapper().readTree(response.getContentAsString(StandardCharsets.UTF_8));
        return body.path("message").asText();
    }

    @Test
    void eachRead_isRefusedAboveItsOwnLimit_withAClearMessage_andServedBelow() throws Exception {
        assertThat(budget.maxResults()).isEqualTo(1000);
        // 1 200 resultats : au-dela des limites de l'export (1 000), du detail complet (833) et des metriques
        // (1 000) ; en deca de celles du rapport (1 666) et de l'analyse dans le temps (3 333).
        Execution big = finished(1200, true);
        String id = big.getId().toString();

        MockHttpServletResponse detail = call("/api/executions/" + id + "?includeResults=true");
        assertThat(detail.getStatus()).isEqualTo(413);
        assertThat(message(detail)).startsWith("Exécution trop volumineuse pour le détail complet des requêtes")
                .contains("1 200 requêtes").contains("limite 833 requêtes");
        for (String export : List.of("/report/export", "/report/export.zip", "/report/xlsx", "/report/pdf")) {
            MockHttpServletResponse refused = call("/api/executions/" + id + export);
            assertThat(refused.getStatus()).as(export).isEqualTo(413);
            assertThat(message(refused)).as(export).startsWith("Exécution trop volumineuse pour l'export du rapport");
        }
        MockHttpServletResponse metrics = call("/api/metrics?executionId=" + id);
        assertThat(metrics.getStatus()).isEqualTo(413);
        assertThat(message(metrics)).startsWith("Exécution trop volumineuse pour les métriques");

        // Servis : le rapport, l'analyse dans le temps, le resume (avec debit et taux d'erreurs).
        assertThat(call("/api/executions/" + id + "/report").getStatus()).isEqualTo(200);
        assertThat(call("/api/executions/" + id + "/timeline").getStatus()).isEqualTo(200);
        MockHttpServletResponse summary = call("/api/executions/" + id + "?includeResults=false");
        assertThat(summary.getStatus()).isEqualTo(200);
        JsonNode s = objectMapper.readTree(summary.getContentAsString(StandardCharsets.UTF_8));
        assertThat(s.get("throughputRps").asDouble()).isEqualTo(20.0);
        assertThat(s.get("errorRatePercent").asDouble()).isZero();

        // Comparaison : la somme des executions comparees compte (2 x 1 000 > 1 666).
        Execution a = finished(1000, false);
        Execution b = finished(1000, false);
        MockHttpServletResponse compare = call("/api/executions/compare?ids=" + a.getId() + "&ids=" + b.getId());
        assertThat(compare.getStatus()).isEqualTo(413);
        assertThat(message(compare)).startsWith("Exécution trop volumineuse pour la comparaison").contains("2 000 requêtes");
        Execution c = finished(100, false);
        assertThat(call("/api/executions/compare?ids=" + a.getId() + "&ids=" + c.getId()).getStatus()).isEqualTo(200);

        // Petite execution : tout est servi, et chaque lecture a rendu sa reservation.
        Execution small = finished(200, true);
        for (String path : List.of("?includeResults=true", "/report", "/timeline", "/report/export", "/report/pdf")) {
            assertThat(call("/api/executions/" + small.getId() + path).getStatus()).as(path).isEqualTo(200);
        }
        assertThat(call("/api/metrics?executionId=" + small.getId()).getStatus()).isEqualTo(200);
        assertThat(budget.reservedForReads()).isZero();
    }

    @Test
    void startupBackfill_skipsAnExecutionTooLargeForTheReport_withAnAuditEntry_andNoErrorLoop() {
        Execution tooLarge = finished(1700, false); // > 1 666 : rapport impossible sur ce serveur
        Execution fine = finished(50, false);

        reportSnapshotService.backfill();
        reportSnapshotService.backfill(); // second passage (redemarrage) : meme resultat, aucune erreur

        assertThat(snapshotRepository.existsById(fine.getId())).isTrue();
        assertThat(snapshotRepository.existsById(tooLarge.getId())).isFalse();
        List<String> audits = auditLogRepository.findAll().stream()
                .filter(l -> tooLarge.getId().toString().equals(l.getTargetId()))
                .map(l -> l.getDescription()).toList();
        assertThat(audits).isNotEmpty().allSatisfy(d -> assertThat(d)
                .startsWith("Rapport figé non calculé au démarrage : exécution trop volumineuse")
                .contains("1 700 requêtes").contains("limite 1 666"));
        assertThat(budget.reservedForReads()).isZero();
    }
}
