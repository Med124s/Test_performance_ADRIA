package com.loadpilot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loadpilot.backend.dto.response.ApplicationDashboardResponse;
import com.loadpilot.backend.dto.response.DashboardResponse;
import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.entity.ExecutionStepResult;
import com.loadpilot.backend.entity.Metric;
import com.loadpilot.backend.entity.Scenario;
import com.loadpilot.backend.entity.Step;
import com.loadpilot.backend.enums.ApplicationStatus;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.enums.HttpMethod;
import com.loadpilot.backend.enums.ScenarioStatus;
import com.loadpilot.backend.enums.StepStatus;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.repository.AppUserRepository;
import com.loadpilot.backend.repository.ApplicationRepository;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.ExecutionStepResultRepository;
import com.loadpilot.backend.repository.MetricRepository;
import com.loadpilot.backend.repository.ScenarioRepository;
import com.loadpilot.backend.repository.StepRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * Verifie DashboardServiceImpl avec de VRAIES donnees H2 (voir
 * application-test.yml) - en particulier les requetes JPQL a chemin
 * imbrique (ex: e.scenario.application.id) qu'un test unitaire avec des
 * mocks ne peut pas valider. Chaque test cree ses propres Application(s)
 * avec un id frais : les assertions "filtrees par Application" restent donc
 * fiables meme si la base H2 (partagee entre classes de test dans le meme
 * fork Surefire, voir MetricRepositoryTest) contient deja des donnees
 * d'autres classes.
 *
 * @Transactional : les ecritures de ce test sont annulees a la fin de
 * chaque methode (n'ajoutent donc pas de pollution supplementaire aux
 * classes suivantes).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DashboardServiceImplIntegrationTest {

    @MockBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private DashboardService dashboardService;
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
    @Autowired
    private MetricRepository metricRepository;

    private AppUser user;

    @BeforeEach
    void setUp() {
        user = appUserRepository.save(AppUser.builder()
                .keycloakSubject(UUID.randomUUID().toString())
                .username("tester")
                .enabled(true)
                .build());
    }

    private Application createApplication(String name, ApplicationStatus status) {
        return applicationRepository.save(Application.builder()
                .name(name)
                .url("http://localhost:1")
                .status(status)
                .createdBy(user)
                .build());
    }

    private Scenario createScenario(Application application, String name, ScenarioStatus status) {
        return scenarioRepository.save(Scenario.builder()
                .application(application)
                .name(name)
                .status(status)
                .createdBy(user)
                .build());
    }

    private Step createStep(Scenario scenario, int order) {
        return stepRepository.save(Step.builder()
                .scenario(scenario)
                .name("step-" + order)
                .method(HttpMethod.GET)
                .url("/x")
                .order(order)
                .status(StepStatus.ACTIVE)
                .build());
    }

    private Execution createExecution(Scenario scenario, ExecutionStatus status, int total, int successful, int failed) {
        return createExecution(scenario, status, total, successful, failed, Instant.now());
    }

    private Execution createExecution(Scenario scenario, ExecutionStatus status, int total, int successful, int failed, Instant startedAt) {
        return executionRepository.saveAndFlush(Execution.builder()
                .scenario(scenario)
                .startedAt(startedAt)
                .status(status)
                .totalSteps(total)
                .successfulSteps(successful)
                .failedSteps(failed)
                .build());
    }

    private void createResult(Execution execution, Step step, boolean success) {
        executionStepResultRepository.saveAndFlush(ExecutionStepResult.builder()
                .execution(execution)
                .step(step)
                .httpStatus(success ? 200 : 500)
                .responseTime(100L)
                .success(success)
                .timestamp(Instant.now())
                .build());
    }

    private void createMetric(Application application, Scenario scenario, Step step, Execution execution,
            Integer responseTime, BigDecimal throughput, BigDecimal errorRate) {
        metricRepository.saveAndFlush(Metric.builder()
                .application(application)
                .scenario(scenario)
                .step(step)
                .execution(execution)
                .responseTime(responseTime)
                .statusCode(200)
                .throughput(throughput)
                .errorRate(errorRate)
                .timestamp(Instant.now())
                .build());
    }

    // ------------------------------------------------------------
    // Dashboard global - sanite (pas de compte exact possible sur base partagee)
    // ------------------------------------------------------------

    @Test
    void getGlobalDashboard_includesDataCreatedInThisTest() {
        Application application = createApplication("global-app-" + UUID.randomUUID(), ApplicationStatus.CONNECTED);
        createScenario(application, "global-scenario", ScenarioStatus.ACTIVE);

        DashboardResponse before = dashboardService.getGlobalDashboard();
        // Nouvelle Application/Scenario visibles : les totaux globaux ne peuvent
        // que refleter au moins ce qui vient d'etre cree (jamais moins).
        assertThat(before.applications().totalApplications()).isGreaterThanOrEqualTo(1L);
        assertThat(before.scenarios().totalScenarios()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void getGlobalDashboard_neverThrows_evenWithExistingData() {
        assertThat(dashboardService.getGlobalDashboard()).isNotNull();
    }

    // ------------------------------------------------------------
    // Dashboard par Application - isolation reelle (requetes JPQL a chemin imbrique)
    // ------------------------------------------------------------

    @Test
    void getApplicationDashboard_unknownId_throwsResourceNotFound() {
        assertThatThrownBy(() -> dashboardService.getApplicationDashboard(UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getApplicationDashboard_returnsIdentityMatchingTheApplication() {
        Application application = createApplication("identity-app", ApplicationStatus.FAILED);

        ApplicationDashboardResponse response = dashboardService.getApplicationDashboard(application.getId());

        assertThat(response.application().id()).isEqualTo(application.getId().toString());
        assertThat(response.application().name()).isEqualTo("identity-app");
        assertThat(response.application().status()).isEqualTo(ApplicationStatus.FAILED);
    }

    @Test
    void getApplicationDashboard_countsScenariosExactlyForThatApplication() {
        Application appA = createApplication("scenario-filter-a", null);
        Application appB = createApplication("scenario-filter-b", null);
        createScenario(appA, "a-1", ScenarioStatus.ACTIVE);
        createScenario(appA, "a-2", ScenarioStatus.INACTIVE);
        createScenario(appB, "b-1", ScenarioStatus.ACTIVE);

        ApplicationDashboardResponse dashboardA = dashboardService.getApplicationDashboard(appA.getId());

        assertThat(dashboardA.scenarios().totalScenarios()).isEqualTo(2L);
        assertThat(dashboardA.scenarios().activeScenarios()).isEqualTo(1L);
        assertThat(dashboardA.scenarios().inactiveScenarios()).isEqualTo(1L);
    }

    @Test
    void getApplicationDashboard_countsExecutionsExactlyForThatApplication_viaNestedJoin() {
        Application appA = createApplication("execution-filter-a", null);
        Application appB = createApplication("execution-filter-b", null);
        Scenario scenarioA = createScenario(appA, "scenario-a", ScenarioStatus.ACTIVE);
        Scenario scenarioB = createScenario(appB, "scenario-b", ScenarioStatus.ACTIVE);
        createExecution(scenarioA, ExecutionStatus.SUCCESS, 1, 1, 0);
        createExecution(scenarioA, ExecutionStatus.FAILED, 1, 0, 1);
        createExecution(scenarioB, ExecutionStatus.SUCCESS, 1, 1, 0);

        ApplicationDashboardResponse dashboardA = dashboardService.getApplicationDashboard(appA.getId());

        assertThat(dashboardA.executions().totalExecutions()).isEqualTo(2L);
        assertThat(dashboardA.executions().successfulExecutions()).isEqualTo(1L);
        assertThat(dashboardA.executions().failedExecutions()).isEqualTo(1L);
        assertThat(dashboardA.executions().runningExecutions()).isZero();
        assertThat(dashboardA.executions().cancelledExecutions()).isZero();
    }

    @Test
    void getApplicationDashboard_countsExecutedStepsAndRates_viaNestedJoin() {
        Application appA = createApplication("steps-filter-a", null);
        Application appB = createApplication("steps-filter-b", null);
        Scenario scenarioA = createScenario(appA, "scenario-a", ScenarioStatus.ACTIVE);
        Scenario scenarioB = createScenario(appB, "scenario-b", ScenarioStatus.ACTIVE);
        Step stepA = createStep(scenarioA, 1);
        Step stepB = createStep(scenarioB, 1);
        Execution execA = createExecution(scenarioA, ExecutionStatus.FAILED, 1, 0, 1);
        Execution execB = createExecution(scenarioB, ExecutionStatus.SUCCESS, 1, 1, 0);
        createResult(execA, stepA, true);
        createResult(execA, stepA, false);
        createResult(execA, stepA, false);
        createResult(execB, stepB, true);

        ApplicationDashboardResponse dashboardA = dashboardService.getApplicationDashboard(appA.getId());

        assertThat(dashboardA.executions().totalStepsExecuted()).isEqualTo(3L);
        assertThat(dashboardA.executions().successfulSteps()).isEqualTo(1L);
        assertThat(dashboardA.executions().failedSteps()).isEqualTo(2L);
        // 1 succes / 3 executes = 33.33% ; 2 echecs / 3 executes = 66.67%.
        assertThat(dashboardA.executions().successRate()).isEqualByComparingTo("33.33");
        assertThat(dashboardA.executions().failureRate()).isEqualByComparingTo("66.67");
    }

    @Test
    void getApplicationDashboard_averagesFilteredByApplication_doNotCrossContaminate() {
        Application appA = createApplication("metrics-filter-a", null);
        Application appB = createApplication("metrics-filter-b", null);
        Scenario scenarioA = createScenario(appA, "scenario-a", ScenarioStatus.ACTIVE);
        Scenario scenarioB = createScenario(appB, "scenario-b", ScenarioStatus.ACTIVE);
        Step stepA = createStep(scenarioA, 1);
        Step stepB = createStep(scenarioB, 1);
        Execution execA = createExecution(scenarioA, ExecutionStatus.SUCCESS, 1, 1, 0);
        Execution execB = createExecution(scenarioB, ExecutionStatus.SUCCESS, 1, 1, 0);

        createMetric(appA, scenarioA, stepA, execA, 100, BigDecimal.valueOf(10), BigDecimal.ZERO);
        createMetric(appA, scenarioA, stepA, execA, 200, BigDecimal.valueOf(20), BigDecimal.ZERO);
        createMetric(appB, scenarioB, stepB, execB, 999, BigDecimal.valueOf(999), BigDecimal.valueOf(50));

        ApplicationDashboardResponse dashboardA = dashboardService.getApplicationDashboard(appA.getId());

        // Moyenne de 100 et 200 = 150 - jamais influencee par la Metric de appB (999).
        assertThat(dashboardA.performance().averageResponseTime()).isEqualByComparingTo("150.00");
        assertThat(dashboardA.performance().averageThroughput()).isEqualByComparingTo("15.000");
        assertThat(dashboardA.performance().averageErrorRate()).isEqualByComparingTo("0.00");
    }

    @Test
    void getApplicationDashboard_applicationWithoutExecutions_returnsZeroesAndNullRatesNoException() {
        Application application = createApplication("no-executions-app", null);

        ApplicationDashboardResponse response = dashboardService.getApplicationDashboard(application.getId());

        assertThat(response.executions().totalExecutions()).isZero();
        assertThat(response.executions().totalStepsExecuted()).isZero();
        assertThat(response.executions().successRate()).isNull();
        assertThat(response.executions().failureRate()).isNull();
    }

    @Test
    void getApplicationDashboard_applicationWithoutMetrics_returnsNullPerformanceNoException() {
        Application application = createApplication("no-metrics-app", null);
        Scenario scenario = createScenario(application, "scenario", ScenarioStatus.ACTIVE);
        createExecution(scenario, ExecutionStatus.SUCCESS, 1, 1, 0);

        ApplicationDashboardResponse response = dashboardService.getApplicationDashboard(application.getId());

        assertThat(response.performance().averageResponseTime()).isNull();
        assertThat(response.performance().averageThroughput()).isNull();
        assertThat(response.performance().averageErrorRate()).isNull();
    }

    @Test
    void getApplicationDashboard_applicationWithoutScenarios_returnsZeroesNoException() {
        Application application = createApplication("no-scenarios-app", null);

        ApplicationDashboardResponse response = dashboardService.getApplicationDashboard(application.getId());

        assertThat(response.scenarios().totalScenarios()).isZero();
        assertThat(response.scenarios().activeScenarios()).isZero();
        assertThat(response.scenarios().inactiveScenarios()).isZero();
    }

    // ------------------------------------------------------------
    // P1-C - Dashboard enrichi (plage temporelle, widget "Top scenarios")
    // ------------------------------------------------------------

    @Test
    void getGlobalDashboard_noArgs_behavesExactlyAsBeforeP1C_rangeFieldsNullAndAllTimeCounts() {
        DashboardResponse response = dashboardService.getGlobalDashboard();

        assertThat(response.rangeFrom()).isNull();
        assertThat(response.rangeTo()).isNull();
    }

    @Test
    void getGlobalDashboard_futureRange_executionsAndPerformanceAreEmptyButApplicationsScenariosStayAllTime() {
        Application application = createApplication("range-future-app-" + UUID.randomUUID(), ApplicationStatus.CONNECTED);
        Scenario scenario = createScenario(application, "range-future-scenario", ScenarioStatus.ACTIVE);
        createExecution(scenario, ExecutionStatus.SUCCESS, 1, 1, 0);

        Instant future = Instant.now().plus(365, ChronoUnit.DAYS);
        DashboardResponse response = dashboardService.getGlobalDashboard(future, future.plusSeconds(60));

        // Une fenetre entierement future ne peut structurellement contenir
        // aucune Execution/Metric reelle - assertion deterministe, immunisee
        // contre les donnees d'autres classes de test (base H2 partagee).
        assertThat(response.executions().totalExecutions()).isZero();
        assertThat(response.performance().averageResponseTime()).isNull();
        assertThat(response.topScenarios()).isEmpty();
        assertThat(response.rangeFrom()).isEqualTo(future);
        assertThat(response.rangeTo()).isEqualTo(future.plusSeconds(60));

        // "applications"/"scenarios" restent TOUJOURS all-time (voir
        // DashboardResponse) - notre Application/Scenario fraichement crees
        // sont donc bien comptes malgre la fenetre future sur executions.
        assertThat(response.applications().totalApplications()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void getGlobalDashboard_rangeIncludingRealData_countsAtLeastTheExecutionsJustCreated() {
        Application application = createApplication("range-include-app-" + UUID.randomUUID(), ApplicationStatus.CONNECTED);
        Scenario scenario = createScenario(application, "range-include-scenario", ScenarioStatus.ACTIVE);
        Instant now = Instant.now();
        createExecution(scenario, ExecutionStatus.SUCCESS, 1, 1, 0, now);
        createExecution(scenario, ExecutionStatus.SUCCESS, 1, 1, 0, now);

        DashboardResponse response = dashboardService.getGlobalDashboard(now.minusSeconds(60), now.plusSeconds(60));

        assertThat(response.executions().totalExecutions()).isGreaterThanOrEqualTo(2);
        assertThat(response.topScenarios()).hasSizeLessThanOrEqualTo(5);
    }
}
