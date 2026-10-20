package com.loadpilot.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

/**
 * ANCIENNE table metric (en fin de vie, conservee tant qu'elle contient des lignes : voir migration 041)
 * - persistance des lignes existantes : les 4 relations et l'integrite referentielle (FK obligatoires vs
 * nullables), sur lesquelles reposent leur purge et leur suppression. Les finders, la recherche et les
 * moyennes sont desormais calcules sur les resultats : voir ResultAggregatesRepositoryTest.
 *
 * AutoConfigureTestDatabase.Replace.NONE : conserve le datasource H2 deja
 * configure par application-test.yml (avec Liquibase) plutot que de le
 * remplacer par un embedded database auto-detecte.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class MetricRepositoryTest {

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
    }

    private Metric.MetricBuilder baseMetric(Instant timestamp) {
        return Metric.builder()
                .application(application)
                .scenario(scenario)
                .step(step)
                .execution(execution)
                .responseTime(120)
                .statusCode(200)
                .throughput(BigDecimal.valueOf(1.5))
                .errorRate(BigDecimal.ZERO)
                .timestamp(timestamp);
    }

    // ------------------------------------------------------------
    // Persistance / relations / integrite
    // ------------------------------------------------------------

    @Test
    void save_withAllFourRelations_persistsAndReloadsCorrectly() {
        Metric saved = metricRepository.saveAndFlush(baseMetric(Instant.now()).build());

        Metric reloaded = metricRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getApplication().getId()).isEqualTo(application.getId());
        assertThat(reloaded.getScenario().getId()).isEqualTo(scenario.getId());
        assertThat(reloaded.getStep().getId()).isEqualTo(step.getId());
        assertThat(reloaded.getExecution().getId()).isEqualTo(execution.getId());
        assertThat(reloaded.getResponseTime()).isEqualTo(120);
        assertThat(reloaded.getStatusCode()).isEqualTo(200);
    }

    @Test
    void save_withoutApplicationOrScenario_isAllowed() {
        Metric metric = baseMetric(Instant.now()).application(null).scenario(null).build();

        Metric saved = metricRepository.saveAndFlush(metric);

        Metric reloaded = metricRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getApplication()).isNull();
        assertThat(reloaded.getScenario()).isNull();
        assertThat(reloaded.getStep().getId()).isEqualTo(step.getId());
        assertThat(reloaded.getExecution().getId()).isEqualTo(execution.getId());
    }

    @Test
    void save_withoutStep_isAllowed_forDeletedSteps() {
        // Migration 017 : step_id nullable (ON DELETE SET NULL) - une metrique
        // survit a la suppression de son etape.
        Metric metric = baseMetric(Instant.now()).step(null).build();

        assertThat(metricRepository.saveAndFlush(metric).getId()).isNotNull();
    }

    @Test
    void save_withoutExecution_violatesNotNullConstraint() {
        Metric metric = baseMetric(Instant.now()).execution(null).build();

        assertThatThrownBy(() -> metricRepository.saveAndFlush(metric))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void save_referencingNonexistentStep_violatesForeignKey() {
        Step danglingRef = stepRepository.getReferenceById(UUID.randomUUID());
        Metric metric = baseMetric(Instant.now()).step(danglingRef).build();

        assertThatThrownBy(() -> metricRepository.saveAndFlush(metric))
                .isInstanceOf(RuntimeException.class);
    }
}
