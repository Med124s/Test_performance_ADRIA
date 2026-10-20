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
import com.loadpilot.backend.service.impl.PerformanceAggregates;
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
 * Agregats et lignes de /api/metrics calcules directement sur execution_step_result (plus de table
 * metric) : filtres combinables, ordre chronologique, plage de dates, regroupement par scenario,
 * executions en cours exclues.
 *
 * Base H2 partagee entre classes de test : les moyennes globales sont verifiees sur des instants de l'an
 * 2000, qu'aucune autre classe n'utilise (meme convention que l'ancien MetricRepositoryTest).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
// SecretCipher : colonnes chiffrees de Application (sinon dependant de l'ordre des classes de test).
@org.springframework.context.annotation.Import(com.loadpilot.backend.security.SecretCipher.class)
class ResultAggregatesRepositoryTest {

    private static final Instant Y2000 = Instant.parse("2000-01-01T00:00:00Z");

    @Autowired private AppUserRepository appUserRepository;
    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private ScenarioRepository scenarioRepository;
    @Autowired private StepRepository stepRepository;
    @Autowired private ExecutionRepository executionRepository;
    @Autowired private ExecutionStepResultRepository resultRepository;

    private Application application;
    private Scenario scenario;
    private Step step;
    private Execution execution;

    @BeforeEach
    void setUp() {
        application = application("app");
        scenario = scenario(application);
        step = stepRepository.save(Step.builder().scenario(scenario).name("step-1").method(HttpMethod.GET).url("/x").order(1)
                .status(StepStatus.ACTIVE).build());
        execution = execution(scenario, ExecutionStatus.SUCCESS);
    }

    private Application application(String tag) {
        AppUser user = appUserRepository.save(AppUser.builder().keycloakSubject(UUID.randomUUID().toString())
                .username(tag + "-" + UUID.randomUUID()).enabled(true).build());
        return applicationRepository.save(Application.builder().name(tag + "-" + UUID.randomUUID()).url("http://localhost:1")
                .createdBy(user).build());
    }

    private Scenario scenario(Application app) {
        return scenarioRepository.save(Scenario.builder().application(app).name("scenario-" + UUID.randomUUID())
                .status(ScenarioStatus.ACTIVE).createdBy(app.getCreatedBy()).build());
    }

    private Execution execution(Scenario s, ExecutionStatus status) {
        return executionRepository.save(Execution.builder().scenario(s).startedAt(Instant.now()).status(status)
                .totalSteps(1).successfulSteps(1).failedSteps(0).duration(1000L).build());
    }

    private ExecutionStepResult result(Execution e, Step st, long responseTime, Instant at) {
        return resultRepository.saveAndFlush(ExecutionStepResult.builder().execution(e).step(st)
                .stepName(st == null ? null : st.getName()).httpStatus(200).responseTime(responseTime).success(true).timestamp(at).build());
    }

    private List<ExecutionStepResult> search(UUID app, UUID sc, UUID st, UUID ex) {
        return resultRepository.searchMetricRows(PerformanceAggregates.ACTIVE, app, sc, st, ex);
    }

    @Test
    void search_eachFilterAlone_andCombinedWithAndLogic() {
        ExecutionStepResult matching = result(execution, step, 120, Instant.now());
        Scenario other = scenario(application("other"));
        Execution otherExecution = execution(other, ExecutionStatus.SUCCESS);
        result(otherExecution, null, 50, Instant.now());

        assertThat(search(application.getId(), null, null, null)).extracting(ExecutionStepResult::getId).containsExactly(matching.getId());
        assertThat(search(null, scenario.getId(), null, null)).extracting(ExecutionStepResult::getId).containsExactly(matching.getId());
        assertThat(search(null, null, step.getId(), null)).extracting(ExecutionStepResult::getId).containsExactly(matching.getId());
        assertThat(search(null, null, null, execution.getId())).extracting(ExecutionStepResult::getId).containsExactly(matching.getId());
        assertThat(search(application.getId(), null, null, execution.getId())).hasSize(1);
        assertThat(search(application.getId(), null, null, UUID.randomUUID())).isEmpty();
        assertThat(search(null, null, null, null)).extracting(ExecutionStepResult::getId).contains(matching.getId());
        assertThat(resultRepository.countMetricRows(PerformanceAggregates.ACTIVE, application.getId(), null, null, null)).isEqualTo(1);
    }

    @Test
    void search_isChronological_andSurvivesADeletedStep() {
        Instant t0 = Instant.now().minus(10, ChronoUnit.MINUTES);
        ExecutionStepResult third = result(execution, step, 1, t0.plusSeconds(20));
        ExecutionStepResult first = result(execution, null, 1, t0);
        ExecutionStepResult second = result(execution, step, 1, t0.plusSeconds(10));
        assertThat(search(null, null, null, execution.getId())).extracting(ExecutionStepResult::getId)
                .containsExactly(first.getId(), second.getId(), third.getId());
    }

    @Test
    void executionsStillRunning_areExcluded_likeTheOldMetricsGeneratedAtTheEnd() {
        for (ExecutionStatus active : PerformanceAggregates.ACTIVE) {
            Execution running = execution(scenario, active);
            result(running, step, 999, Y2000);
            assertThat(search(null, null, null, running.getId())).as(active.name()).isEmpty();
        }
        PerformanceAggregates.Averages averages = new PerformanceAggregates(resultRepository)
                .between(Y2000.minusSeconds(1), Y2000.plusSeconds(1));
        assertThat(averages.responseTime()).as("aucune requete d'execution en cours dans les moyennes").isNull();
        assertThat(new PerformanceAggregates(resultRepository).forApplication(application.getId()).responseTime()).isNull();
    }

    @Test
    void averagesOverARange_onlyCountResultsInsideIt() {
        result(execution, step, 100, Y2000);
        result(execution, step, 200, Y2000);
        // Hors fenetre - ne doit jamais entrer dans la moyenne.
        result(execution, step, 99_999, Y2000.minus(10, ChronoUnit.DAYS));

        PerformanceAggregates.Averages averages = new PerformanceAggregates(resultRepository)
                .between(Y2000.minusSeconds(60), Y2000.plusSeconds(60));
        assertThat(averages.responseTime()).isEqualTo(150.0);

        Instant future = Instant.now().plus(365, ChronoUnit.DAYS);
        PerformanceAggregates.Averages none = new PerformanceAggregates(resultRepository).between(future, future.plusSeconds(60));
        assertThat(none.responseTime()).isNull();
        assertThat(none.throughput()).isNull();
        assertThat(none.errorRate()).isNull();
    }

    @Test
    void averageResponseTimeByScenario_groupsAndRespectsTheRange() {
        Instant now = Instant.now();
        result(execution, step, 100, now);
        result(execution, step, 300, now);
        result(execution, step, 99_999, now.minus(10, ChronoUnit.DAYS));

        List<ScenarioAverageResponseTimeProjection> rows = resultRepository.averageResponseTimeByScenarioIn(
                PerformanceAggregates.ACTIVE, List.of(scenario.getId()), now.minusSeconds(60), now.plusSeconds(60));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getScenarioId()).isEqualTo(scenario.getId());
        assertThat(rows.get(0).getAverageResponseTime()).isEqualTo(200.0);

        assertThat(resultRepository.averageResponseTimeByScenarioIn(PerformanceAggregates.ACTIVE, List.of(UUID.randomUUID()),
                now.minusSeconds(60), now.plusSeconds(60))).isEmpty();
    }
}
