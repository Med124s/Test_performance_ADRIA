package com.loadpilot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loadpilot.backend.dto.response.MetricResponse;
import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.entity.Metric;
import com.loadpilot.backend.entity.Scenario;
import com.loadpilot.backend.entity.Step;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.enums.HttpMethod;
import com.loadpilot.backend.enums.ScenarioStatus;
import com.loadpilot.backend.enums.StepStatus;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.repository.AppUserRepository;
import com.loadpilot.backend.repository.ApplicationRepository;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.MetricRepository;
import com.loadpilot.backend.repository.ScenarioRepository;
import com.loadpilot.backend.repository.StepRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifie MetricServiceImpl directement (sans HTTP) : donnees inserees via
 * les repositories, jamais via des mocks - seule la couche service est en
 * jeu ici (voir MetricControllerTest pour le bout-en-bout HTTP complet).
 *
 * JwtDecoder est mocke (jamais utilise directement ici) uniquement parce
 * que @SpringBootTest demarre le contexte complet, y compris SecurityConfig
 * qui exige un bean JwtDecoder - meme necessite que dans les *ControllerTest
 * (voir Phase 4), aucun Keycloak reel requis.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MetricServiceImplTest {

    @MockBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private MetricService metricService;
    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private ApplicationRepository applicationRepository;
    @Autowired
    private ScenarioRepository scenarioRepository;
    @Autowired
    private StepRepository stepRepository;
    @Autowired
    private ExecutionRepository executionRepository;
    @Autowired
    private MetricRepository metricRepository;

    private Application application;
    private Scenario scenario;
    private Step step;
    private Execution execution;
    private Metric metric;

    @BeforeEach
    void setUp() {
        AppUser user = appUserRepository.save(AppUser.builder()
                .keycloakSubject(UUID.randomUUID().toString())
                .username("tester")
                .enabled(true)
                .build());
        application = applicationRepository.save(Application.builder()
                .name("app-" + UUID.randomUUID())
                .url("http://localhost:1")
                .createdBy(user)
                .build());
        scenario = scenarioRepository.save(Scenario.builder()
                .application(application)
                .name("scenario-" + UUID.randomUUID())
                .status(ScenarioStatus.ACTIVE)
                .createdBy(user)
                .build());
        step = stepRepository.save(Step.builder()
                .scenario(scenario)
                .name("step-1")
                .method(HttpMethod.GET)
                .url("/x")
                .order(1)
                .status(StepStatus.ACTIVE)
                .build());
        execution = executionRepository.save(Execution.builder()
                .scenario(scenario)
                .startedAt(Instant.now())
                .status(ExecutionStatus.SUCCESS)
                .totalSteps(1)
                .successfulSteps(1)
                .failedSteps(0)
                .build());
        metric = metricRepository.saveAndFlush(Metric.builder()
                .application(application)
                .scenario(scenario)
                .step(step)
                .execution(execution)
                .responseTime(150)
                .statusCode(200)
                .throughput(BigDecimal.valueOf(2))
                .errorRate(BigDecimal.ZERO)
                .timestamp(Instant.now())
                .build());
    }

    @Test
    void getAll_includesTheSeededMetric() {
        List<MetricResponse> result = metricService.getAll();

        assertThat(result).extracting(MetricResponse::id).contains(metric.getId().toString());
    }

    @Test
    void getById_returnsMappedResponse() {
        MetricResponse response = metricService.getById(metric.getId());

        assertThat(response.id()).isEqualTo(metric.getId().toString());
        assertThat(response.applicationId()).isEqualTo(application.getId().toString());
        assertThat(response.scenarioId()).isEqualTo(scenario.getId().toString());
        assertThat(response.stepId()).isEqualTo(step.getId().toString());
        assertThat(response.executionId()).isEqualTo(execution.getId().toString());
        assertThat(response.responseTime()).isEqualTo(150);
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void getById_unknownId_throwsResourceNotFound() {
        assertThatThrownBy(() -> metricService.getById(UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getByApplication_returnsFilteredMetrics() {
        List<MetricResponse> result = metricService.getByApplication(application.getId());

        assertThat(result).extracting(MetricResponse::id).containsExactly(metric.getId().toString());
    }

    @Test
    void getByApplication_nonexistentApplication_throwsResourceNotFound() {
        assertThatThrownBy(() -> metricService.getByApplication(UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getByScenario_returnsFilteredMetrics() {
        List<MetricResponse> result = metricService.getByScenario(scenario.getId());

        assertThat(result).extracting(MetricResponse::id).containsExactly(metric.getId().toString());
    }

    @Test
    void getByScenario_nonexistentScenario_throwsResourceNotFound() {
        assertThatThrownBy(() -> metricService.getByScenario(UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getByStep_returnsFilteredMetrics() {
        List<MetricResponse> result = metricService.getByStep(step.getId());

        assertThat(result).extracting(MetricResponse::id).containsExactly(metric.getId().toString());
    }

    @Test
    void getByStep_nonexistentStep_throwsResourceNotFound() {
        assertThatThrownBy(() -> metricService.getByStep(UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getByExecution_returnsFilteredMetrics() {
        List<MetricResponse> result = metricService.getByExecution(execution.getId());

        assertThat(result).extracting(MetricResponse::id).containsExactly(metric.getId().toString());
    }

    @Test
    void getByExecution_nonexistentExecution_throwsResourceNotFound() {
        assertThatThrownBy(() -> metricService.getByExecution(UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void search_withMatchingCombinedFilters_returnsMetric() {
        List<MetricResponse> result = metricService.search(application.getId(), scenario.getId(), step.getId(), execution.getId());

        assertThat(result).extracting(MetricResponse::id).containsExactly(metric.getId().toString());
    }

    @Test
    void search_withNonexistentFilterId_throwsResourceNotFound() {
        UUID randomId = UUID.randomUUID();
        assertThatThrownBy(() -> metricService.search(null, null, null, randomId))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
