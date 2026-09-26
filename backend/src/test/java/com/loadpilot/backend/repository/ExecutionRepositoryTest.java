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

/**
 * Verifie ExecutionRepository avec H2 reel - en particulier
 * findByIdWithScenarioAndApplication (JOIN FETCH, Phase 9) et les
 * agregations par Application via jointure imbriquee
 * (e.scenario.application.id, Phase 11), jamais testees isolement jusqu'ici.
 */
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

        // Acces direct sans exception (JOIN FETCH deja initialise, pas de
        // proxy Hibernate qui declencherait une requete/LazyInitializationException).
        assertThat(reloaded.getScenario().getName()).isEqualTo(scenario.getName());
        assertThat(reloaded.getScenario().getApplication().getUrl()).isEqualTo(application.getUrl());
    }

    @Test
    void findByIdWithScenarioAndApplication_unknownId_returnsEmpty() {
        assertThat(executionRepository.findByIdWithScenarioAndApplication(UUID.randomUUID())).isEmpty();
    }

    @Test
    void findByScenarioId_ordersByStartedAtDescending() {
        // Instants explicitement distincts (jamais Instant.now() appele deux
        // fois de suite) : sur certains systemes (Windows notamment), deux
        // appels rapproches a Instant.now() peuvent renvoyer la MEME valeur
        // (granularite de l'horloge systeme), ce qui rendrait ce test flaky
        // s'il dependait de l'ordre de creation reel plutot que de valeurs
        // controlees.
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

    // ------------------------------------------------------------
    // P1-C - Dashboard enrichi (plage temporelle, widget "Top scenarios")
    // ------------------------------------------------------------

    @Test
    void countByStartedAtBetween_and_countByStatusAndStartedAtBetween_respectTheRange() {
        Instant now = Instant.now();
        save(scenario, ExecutionStatus.SUCCESS, now);
        save(scenario, ExecutionStatus.FAILED, now);
        // En dehors de la fenetre testee - ne doit jamais etre compte.
        save(scenario, ExecutionStatus.SUCCESS, now.minus(10, ChronoUnit.DAYS));

        Instant from = now.minusSeconds(60);
        Instant to = now.plusSeconds(60);

        assertThat(executionRepository.countByStartedAtBetween(from, to)).isGreaterThanOrEqualTo(2L);
        assertThat(executionRepository.countByStatusAndStartedAtBetween(ExecutionStatus.SUCCESS, from, to)).isGreaterThanOrEqualTo(1L);

        // Une fenetre entierement dans le futur ne peut structurellement
        // contenir aucune Execution reelle (aucun test ne cree jamais de
        // startedAt futur) - assertion deterministe, jamais affectee par
        // les donnees d'autres classes de test partageant la meme base H2.
        Instant future = now.plus(365, ChronoUnit.DAYS);
        assertThat(executionRepository.countByStartedAtBetween(future, future.plusSeconds(60))).isZero();
    }

    @Test
    void findTopScenariosByExecutionCount_countsExecutionsAndSuccessesForThisScenario_andExcludesOutOfRangeOnes() {
        Instant now = Instant.now();
        save(scenario, ExecutionStatus.SUCCESS, now);
        save(scenario, ExecutionStatus.SUCCESS, now);
        save(scenario, ExecutionStatus.FAILED, now);
        // Hors de la fenetre testee - ne doit jamais etre compte dans le groupe.
        save(scenario, ExecutionStatus.SUCCESS, now.minus(10, ChronoUnit.DAYS));

        Instant from = now.minusSeconds(60);
        Instant to = now.plusSeconds(60);

        // Limite large (jamais TOP_SCENARIOS_LIMIT=5 du service) : immunise
        // ce test contre d'autres scenarios crees par d'autres classes dans
        // la meme fenetre temporelle (base H2 partagee) - on cherche
        // explicitement NOTRE scenario dans la liste complete, jamais
        // suppose qu'il soit dans un "top 5" partage.
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
