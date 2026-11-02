package com.loadpilot.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.entity.Scenario;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.enums.ScenarioStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

/** ExecutionRepository sur H2 : chargement avec JOIN FETCH et agregations par application. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ExecutionRepositoryTest {

    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private ApplicationRepository applicationRepository;
    @Autowired
    private ScenarioRepository scenarioRepository;
    @Autowired
    private ExecutionRepository executionRepository;

    private AppUser user;
    private Application application;
    private Scenario scenario;

    @BeforeEach
    void setUp() {
        user = appUserRepository.save(AppUser.builder()
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
    }

    private Execution save(Scenario s, ExecutionStatus status) {
        return save(s, status, Instant.now());
    }

    private Execution save(Scenario s, ExecutionStatus status, Instant startedAt) {
        return executionRepository.saveAndFlush(Execution.builder()
                .scenario(s)
                .startedAt(startedAt)
                .status(status)
                .totalSteps(1)
                .successfulSteps(status == ExecutionStatus.SUCCESS ? 1 : 0)
                .failedSteps(status == ExecutionStatus.FAILED ? 1 : 0)
                .build());
    }

    private Scenario otherApplicationScenario() {
        Application otherApp = applicationRepository.save(Application.builder()
                .name("other-app-" + UUID.randomUUID())
                .url("http://localhost:2")
                .createdBy(user)
                .build());
        return scenarioRepository.save(Scenario.builder()
                .application(otherApp)
                .name("other-scenario-" + UUID.randomUUID())
                .status(ScenarioStatus.ACTIVE)
                .createdBy(user)
                .build());
    }

    @Test
    void findByIdWithScenarioAndApplication_eagerlyLoadsScenarioAndApplication() {
        Execution saved = save(scenario, ExecutionStatus.SUCCESS);

        Execution reloaded = executionRepository.findByIdWithScenarioAndApplication(saved.getId()).orElseThrow();

        // Scenario deja charge (JOIN FETCH).
        assertThat(reloaded.getScenario().getName()).isEqualTo(scenario.getName());
        assertThat(reloaded.getScenario().getApplication().getUrl()).isEqualTo(application.getUrl());
    }

    @Test
    void findByIdWithScenarioAndApplication_unknownId_returnsEmpty() {
        assertThat(executionRepository.findByIdWithScenarioAndApplication(UUID.randomUUID())).isEmpty();
    }

    @Test
    void findByScenarioId_ordersByStartedAtDescending() {
        // Instants explicitement distincts : deux appels rapproches a Instant.now() peuvent renvoyer la meme valeur.
        Instant base = Instant.now();
        Execution first = save(scenario, ExecutionStatus.SUCCESS, base);
        Execution second = save(scenario, ExecutionStatus.SUCCESS, base.plusSeconds(10));

        List<Execution> result = executionRepository.findByScenarioId(
                scenario.getId(), Sort.by(Sort.Direction.DESC, "startedAt"));

        assertThat(result).extracting(Execution::getId).containsExactly(second.getId(), first.getId());
    }

    @Test
    void countByStatus_deltaCountsOnlyMatchingStatus() {
        long runningBefore = executionRepository.countByStatus(ExecutionStatus.RUNNING);
        long successBefore = executionRepository.countByStatus(ExecutionStatus.SUCCESS);

        save(scenario, ExecutionStatus.RUNNING);
        save(scenario, ExecutionStatus.SUCCESS);
        save(scenario, ExecutionStatus.SUCCESS);

        assertThat(executionRepository.countByStatus(ExecutionStatus.RUNNING)).isEqualTo(runningBefore + 1);
        assertThat(executionRepository.countByStatus(ExecutionStatus.SUCCESS)).isEqualTo(successBefore + 2);
    }

    @Test
    void countByApplicationId_usesNestedJoinThroughScenario() {
        Scenario otherScenario = otherApplicationScenario();
        save(scenario, ExecutionStatus.SUCCESS);
        save(scenario, ExecutionStatus.FAILED);
        save(otherScenario, ExecutionStatus.SUCCESS);

        assertThat(executionRepository.countByApplicationId(application.getId())).isEqualTo(2L);
    }

    @Test
    void countByApplicationIdAndStatus_combinesNestedJoinAndStatusFilter() {
        Scenario otherScenario = otherApplicationScenario();
        save(scenario, ExecutionStatus.SUCCESS);
        save(scenario, ExecutionStatus.FAILED);
        save(otherScenario, ExecutionStatus.SUCCESS);

        assertThat(executionRepository.countByApplicationIdAndStatus(application.getId(), ExecutionStatus.SUCCESS)).isEqualTo(1L);
        assertThat(executionRepository.countByApplicationIdAndStatus(application.getId(), ExecutionStatus.FAILED)).isEqualTo(1L);
        assertThat(executionRepository.countByApplicationIdAndStatus(application.getId(), ExecutionStatus.CANCELLED)).isZero();
    }

    @Test
    void countByApplicationId_applicationWithoutExecutions_isZero() {
        Scenario freshScenario = otherApplicationScenario();

        assertThat(executionRepository.countByApplicationId(freshScenario.getApplication().getId())).isZero();
    }

    // ---- Tableau de bord sur une periode ----

    @Test
    void countByStartedAtBetween_and_countByStatusAndStartedAtBetween_respectTheRange() {
        Instant now = Instant.now();
        save(scenario, ExecutionStatus.SUCCESS, now);
        save(scenario, ExecutionStatus.FAILED, now);
        // Hors de la fenetre testee.
        save(scenario, ExecutionStatus.SUCCESS, now.minus(10, ChronoUnit.DAYS));

        Instant from = now.minusSeconds(60);
        Instant to = now.plusSeconds(60);

        assertThat(executionRepository.countByStartedAtBetween(from, to)).isGreaterThanOrEqualTo(2L);
        assertThat(executionRepository.countByStatusAndStartedAtBetween(ExecutionStatus.SUCCESS, from, to)).isGreaterThanOrEqualTo(1L);

        // Une fenetre entierement future ne contient aucune execution.
        Instant future = now.plus(365, ChronoUnit.DAYS);
        assertThat(executionRepository.countByStartedAtBetween(future, future.plusSeconds(60))).isZero();
    }

    @Test
    void findTopScenariosByExecutionCount_countsExecutionsAndSuccessesForThisScenario_andExcludesOutOfRangeOnes() {
        Instant now = Instant.now();
        save(scenario, ExecutionStatus.SUCCESS, now);
        save(scenario, ExecutionStatus.SUCCESS, now);
        save(scenario, ExecutionStatus.FAILED, now);
        // Hors de la fenetre testee.
        save(scenario, ExecutionStatus.SUCCESS, now.minus(10, ChronoUnit.DAYS));

        Instant from = now.minusSeconds(60);
        Instant to = now.plusSeconds(60);

        // Limite large : d'autres scenarios peuvent exister dans la meme fenetre (base partagee).
        List<ScenarioExecutionCountProjection> all = executionRepository.findTopScenariosByExecutionCount(
                from, to, ExecutionStatus.SUCCESS, PageRequest.of(0, 1000));

        Optional<ScenarioExecutionCountProjection> mine = all.stream()
                .filter(p -> p.getScenarioId().equals(scenario.getId()))
                .findFirst();
        assertThat(mine).isPresent();
        assertThat(mine.get().getExecutionCount()).isEqualTo(3L);
        assertThat(mine.get().getSuccessCount()).isEqualTo(2L);
        assertThat(mine.get().getScenarioName()).isEqualTo(scenario.getName());
        assertThat(mine.get().getApplicationName()).isEqualTo(application.getName());
    }

    @Test
    void findTopScenariosByExecutionCount_futureRange_isEmpty() {
        save(scenario, ExecutionStatus.SUCCESS);

        Instant future = Instant.now().plus(365, ChronoUnit.DAYS);
        List<ScenarioExecutionCountProjection> result = executionRepository.findTopScenariosByExecutionCount(
                future, future.plusSeconds(60), ExecutionStatus.SUCCESS, PageRequest.of(0, 5));

        assertThat(result).isEmpty();
    }
}
