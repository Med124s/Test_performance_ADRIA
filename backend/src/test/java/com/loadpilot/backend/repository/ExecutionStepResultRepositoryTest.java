package com.loadpilot.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.entity.ExecutionStepResult;
import com.loadpilot.backend.entity.Scenario;
import com.loadpilot.backend.entity.Step;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.enums.HttpMethod;
import com.loadpilot.backend.enums.ScenarioStatus;
import com.loadpilot.backend.enums.StepStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifie ExecutionStepResultRepository avec H2 reel - notamment
 * findByExecutionIdOrderByTimestampAsc (JOIN FETCH, Phase 9) et les
 * agregations par succes/application via double-jointure imbriquee
 * (r.execution.scenario.application.id, Phase 11).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ExecutionStepResultRepositoryTest {

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
    private ExecutionStepResultRepository executionStepResultRepository;

    private Application application;
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
        Scenario scenario = scenarioRepository.save(Scenario.builder()
                .application(application)
                .name("scenario-" + UUID.randomUUID())
                .status(ScenarioStatus.ACTIVE)
                .createdBy(user)
                .build());
        step = stepRepository.save(Step.builder()
                .scenario(scenario)
                .name("step")
                .method(HttpMethod.GET)
                .url("/x")
                .order(1)
                .status(StepStatus.ACTIVE)
                .build());
        execution = executionRepository.saveAndFlush(Execution.builder()
                .scenario(scenario)
                .startedAt(Instant.now())
                .status(ExecutionStatus.SUCCESS)
                .totalSteps(1)
                .successfulSteps(1)
                .failedSteps(0)
                .build());
    }

    private ExecutionStepResult save(boolean success, Instant timestamp) {
        return executionStepResultRepository.saveAndFlush(ExecutionStepResult.builder()
                .execution(execution)
                .step(step)
                .httpStatus(success ? 200 : 500)
                .responseTime(100L)
                .success(success)
                .timestamp(timestamp)
                .build());
    }

    @Test
    void save_persistsAllFieldsAndRelations() {
        ExecutionStepResult saved = save(true, Instant.now());

        ExecutionStepResult reloaded = executionStepResultRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getHttpStatus()).isEqualTo(200);
        assertThat(reloaded.getSuccess()).isTrue();
        assertThat(reloaded.getExecution().getId()).isEqualTo(execution.getId());
        assertThat(reloaded.getStep().getId()).isEqualTo(step.getId());
    }

    @Test
    void findByExecutionIdOrderByTimestampAsc_returnsChronologicalOrder_withStepEagerlyLoaded() {
        Instant t0 = Instant.now().minus(1, ChronoUnit.HOURS);
        ExecutionStepResult second = save(true, t0.plusSeconds(10));
        ExecutionStepResult first = save(true, t0);

        List<ExecutionStepResult> result = executionStepResultRepository.findByExecutionIdOrderByTimestampAsc(execution.getId());

        assertThat(result).extracting(ExecutionStepResult::getId).containsExactly(first.getId(), second.getId());
        assertThat(result.get(0).getStep().getName()).isEqualTo("step");
    }

    @Test
    void countBySuccess_deltaCountsOnlyMatchingOutcome() {
        long successBefore = executionStepResultRepository.countBySuccess(true);
        long failureBefore = executionStepResultRepository.countBySuccess(false);

        save(true, Instant.now());
        save(true, Instant.now());
        save(false, Instant.now());

        assertThat(executionStepResultRepository.countBySuccess(true)).isEqualTo(successBefore + 2);
        assertThat(executionStepResultRepository.countBySuccess(false)).isEqualTo(failureBefore + 1);
    }

    @Test
    void countByApplicationId_usesDoubleNestedJoin() {
        save(true, Instant.now());
        save(false, Instant.now());

        assertThat(executionStepResultRepository.countByApplicationId(application.getId())).isEqualTo(2L);
    }

    @Test
    void countByApplicationIdAndSuccess_combinesDoubleNestedJoinAndSuccessFilter() {
        save(true, Instant.now());
        save(true, Instant.now());
        save(false, Instant.now());

        assertThat(executionStepResultRepository.countByApplicationIdAndSuccess(application.getId(), true)).isEqualTo(2L);
        assertThat(executionStepResultRepository.countByApplicationIdAndSuccess(application.getId(), false)).isEqualTo(1L);
    }

    @Test
    void findByExecutionIdOrderByTimestampAsc_executionWithoutResults_returnsEmpty() {
        Execution emptyExecution = executionRepository.saveAndFlush(Execution.builder()
                .scenario(execution.getScenario())
                .startedAt(Instant.now())
                .status(ExecutionStatus.RUNNING)
                .totalSteps(1)
                .successfulSteps(0)
                .failedSteps(0)
                .build());

        assertThat(executionStepResultRepository.findByExecutionIdOrderByTimestampAsc(emptyExecution.getId())).isEmpty();
    }
}
