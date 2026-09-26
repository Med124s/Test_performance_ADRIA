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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifie la persistance reelle de Metric (H2 + Liquibase, voir
 * application-test.yml) : les 4 relations, l'integrite referentielle (FK
 * obligatoires vs nullables), tous les finders et l'ordre temporel.
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
    void save_withoutStep_violatesNotNullConstraint() {
        Metric metric = baseMetric(Instant.now()).step(null).build();

        assertThatThrownBy(() -> metricRepository.saveAndFlush(metric))
                .isInstanceOf(DataIntegrityViolationException.class);
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

    // ------------------------------------------------------------
    // Finders
    // ------------------------------------------------------------

    @Test
    void findByApplicationId_returnsOnlyMatchingMetrics() {
        Metric matching = metricRepository.saveAndFlush(baseMetric(Instant.now()).build());
        Application otherApp = otherApplicationChain();
        metricRepository.saveAndFlush(baseMetric(Instant.now()).application(otherApp).build());

        List<Metric> result = metricRepository.findByApplicationId(application.getId(), Sort.unsorted());

        assertThat(result).extracting(Metric::getId).containsExactly(matching.getId());
    }

    @Test
    void findByScenarioId_returnsOnlyMatchingMetrics() {
        Metric matching = metricRepository.saveAndFlush(baseMetric(Instant.now()).build());

        List<Metric> result = metricRepository.findByScenarioId(scenario.getId(), Sort.unsorted());

        assertThat(result).extracting(Metric::getId).containsExactly(matching.getId());
    }

    @Test
    void findByStepId_returnsOnlyMatchingMetrics() {
        Metric matching = metricRepository.saveAndFlush(baseMetric(Instant.now()).build());

        List<Metric> result = metricRepository.findByStepId(step.getId(), Sort.unsorted());

        assertThat(result).extracting(Metric::getId).containsExactly(matching.getId());
    }

    @Test
    void findByExecutionId_returnsOnlyMatchingMetrics() {
        Metric matching = metricRepository.saveAndFlush(baseMetric(Instant.now()).build());

        List<Metric> result = metricRepository.findByExecutionId(execution.getId(), Sort.unsorted());

        assertThat(result).extracting(Metric::getId).containsExactly(matching.getId());
    }

    @Test
    void findByExecutionId_withSortByTimestampAsc_returnsChronologicalOrder() {
        Instant t0 = Instant.now().minus(10, ChronoUnit.MINUTES);
        Metric third = metricRepository.saveAndFlush(baseMetric(t0.plusSeconds(20)).build());
        Metric first = metricRepository.saveAndFlush(baseMetric(t0).build());
        Metric second = metricRepository.saveAndFlush(baseMetric(t0.plusSeconds(10)).build());

        List<Metric> result = metricRepository.findByExecutionId(execution.getId(), Sort.by(Sort.Direction.ASC, "timestamp"));

        assertThat(result).extracting(Metric::getId)
                .containsExactly(first.getId(), second.getId(), third.getId());
    }

    // ------------------------------------------------------------
    // Filtre combinable (search)
    // ------------------------------------------------------------

    @Test
    void search_withNoFilters_includesAllCreatedMetrics() {
        // Base H2 partagee entre classes de test (voir application-test.yml,
        // DB_CLOSE_DELAY=-1) : d'autres classes (ex: MetricControllerTest,
        // qui commit reellement via HTTP) peuvent deja avoir laisse des
        // lignes visibles ici - on verifie donc une inclusion, jamais un
        // compte exact global (meme convention que
        // ExecutionControllerTest#list_includesCreatedExecution).
        Metric first = metricRepository.saveAndFlush(baseMetric(Instant.now()).build());
        Metric second = metricRepository.saveAndFlush(baseMetric(Instant.now()).build());

        List<Metric> result = metricRepository.search(null, null, null, null, Sort.unsorted());

        assertThat(result).extracting(Metric::getId).contains(first.getId(), second.getId());
    }

    @Test
    void search_withApplicationAndExecutionFilters_appliesAndLogic() {
        Metric matching = metricRepository.saveAndFlush(baseMetric(Instant.now()).build());
        Application otherApp = otherApplicationChain();
        metricRepository.saveAndFlush(baseMetric(Instant.now()).application(otherApp).build());

        List<Metric> result = metricRepository.search(application.getId(), null, null, execution.getId(), Sort.unsorted());

        assertThat(result).extracting(Metric::getId).containsExactly(matching.getId());
    }

    @Test
    void search_withNonMatchingCombination_returnsEmpty() {
        metricRepository.saveAndFlush(baseMetric(Instant.now()).build());

        List<Metric> result = metricRepository.search(application.getId(), null, null, UUID.randomUUID(), Sort.unsorted());

        assertThat(result).isEmpty();
    }

    private Application otherApplicationChain() {
        AppUser user = appUserRepository.save(AppUser.builder()
                .keycloakSubject(UUID.randomUUID().toString())
                .username("tester-2")
                .enabled(true)
                .build());
        return applicationRepository.save(Application.builder()
                .name("other-app-" + UUID.randomUUID())
                .url("http://localhost:2")
                .createdBy(user)
                .build());
    }

    // ------------------------------------------------------------
    // P1-C - Dashboard enrichi (plage temporelle, widget "Top scenarios")
    // ------------------------------------------------------------

    /**
     * IMPORTANT (constat verifie) : "averageResponseTimeBetween" est une
     * agregation GLOBALE, volontairement sans filtre par scenario (memes
     * capacites que l'existant averageResponseTime() - voir
     * DashboardServiceImpl). La base H2 de test est PARTAGEE entre TOUTES
     * les classes de test au sein du meme fork Surefire
     * (DB_CLOSE_DELAY=-1, voir application-test.yml) : de nombreuses autres
     * classes (ExecutionControllerTest, ExecutionHistoryAndReportTest...)
     * committent reellement des Metric via de vraies executions HTTP
     * pendant toute la duree de la suite. Une fenetre batie sur
     * Instant.now() +-60s attraperait donc, de maniere non deterministe,
     * les Metric d'autres classes executees a peu pres au meme moment reel
     * (constate : une moyenne polluee, 887.2 au lieu de 150.0 attendu).
     * CHOIX : utiliser un instant FIXE tres eloigne dans le passe (an 2000)
     * pour CES metric de test - aucune autre classe de la suite n'utilise
     * jamais cette epoque (toutes utilisent Instant.now() ou un decalage
     * relatif) - isolation reelle et deterministe, jamais un simple espoir
     * de timing favorable.
     */
    @Test
    void averageResponseTimeBetween_onlyAveragesMetricsInsideTheRange() {
        Instant isolatedInstant = Instant.parse("2000-01-01T00:00:00Z");
        metricRepository.saveAndFlush(baseMetric(isolatedInstant).responseTime(100).build());
        metricRepository.saveAndFlush(baseMetric(isolatedInstant).responseTime(200).build());
        // Hors fenetre - ne doit jamais entrer dans la moyenne.
        metricRepository.saveAndFlush(baseMetric(isolatedInstant.minus(10, ChronoUnit.DAYS)).responseTime(99999).build());

        Double avg = metricRepository.averageResponseTimeBetween(isolatedInstant.minusSeconds(60), isolatedInstant.plusSeconds(60));
        assertThat(avg).isEqualTo(150.0);

        // Fenetre future : structurellement aucune Metric reelle ne peut
        // avoir un timestamp futur - assertion deterministe malgre la base
        // H2 partagee entre classes de test.
        Instant future = Instant.now().plus(365, ChronoUnit.DAYS);
        assertThat(metricRepository.averageResponseTimeBetween(future, future.plusSeconds(60))).isNull();
    }

    @Test
    void averageResponseTimeByScenarioIn_groupsCorrectlyAndRespectsTheRange() {
        Instant now = Instant.now();
        metricRepository.saveAndFlush(baseMetric(now).responseTime(100).build());
        metricRepository.saveAndFlush(baseMetric(now).responseTime(300).build());
        // Hors fenetre - ne doit jamais entrer dans la moyenne de ce scenario.
        metricRepository.saveAndFlush(baseMetric(now.minus(10, ChronoUnit.DAYS)).responseTime(99999).build());

        List<ScenarioAverageResponseTimeProjection> rows = metricRepository.averageResponseTimeByScenarioIn(
                List.of(scenario.getId()), now.minusSeconds(60), now.plusSeconds(60));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getScenarioId()).isEqualTo(scenario.getId());
        assertThat(rows.get(0).getAverageResponseTime()).isEqualTo(200.0);
    }

    @Test
    void averageResponseTimeByScenarioIn_scenarioNotInRequestedList_isExcluded() {
        metricRepository.saveAndFlush(baseMetric(Instant.now()).responseTime(100).build());

        List<ScenarioAverageResponseTimeProjection> rows = metricRepository.averageResponseTimeByScenarioIn(
                List.of(UUID.randomUUID()), Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));

        assertThat(rows).isEmpty();
    }
}
