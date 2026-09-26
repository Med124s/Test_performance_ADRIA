package com.loadpilot.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.entity.Scenario;
import com.loadpilot.backend.entity.Step;
import com.loadpilot.backend.enums.HttpMethod;
import com.loadpilot.backend.enums.ScenarioStatus;
import com.loadpilot.backend.enums.StepStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifie StepRepository avec H2 reel - en particulier l'ORDRE DETERMINISTE
 * (step_order ASC, id ASC en cas d'egalite, voir Phase 8) jamais teste au
 * niveau repository jusqu'ici (seulement indirectement via
 * StepControllerTest/ExecutionEngine).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class StepRepositoryTest {

    private static final Sort ORDER_ASC =
            Sort.by(Sort.Direction.ASC, "order").and(Sort.by(Sort.Direction.ASC, "id"));

    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private ApplicationRepository applicationRepository;
    @Autowired
    private ScenarioRepository scenarioRepository;
    @Autowired
    private StepRepository stepRepository;

    private Scenario scenario;

    @BeforeEach
    void setUp() {
        AppUser user = appUserRepository.save(AppUser.builder()
                .keycloakSubject(UUID.randomUUID().toString())
                .username("tester")
                .enabled(true)
                .build());
        Application application = applicationRepository.save(Application.builder()
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

    private Step save(Scenario s, String name, int order) {
        return stepRepository.saveAndFlush(Step.builder()
                .scenario(s)
                .name(name)
                .method(HttpMethod.GET)
                .url("/x")
                .order(order)
                .status(StepStatus.ACTIVE)
                .build());
    }

    private Scenario otherScenario() throws Exception {
        AppUser user = appUserRepository.save(AppUser.builder()
                .keycloakSubject(UUID.randomUUID().toString())
                .username("tester-2")
                .enabled(true)
                .build());
        Application app = applicationRepository.save(Application.builder()
                .name("other-app-" + UUID.randomUUID())
                .url("http://localhost:2")
                .createdBy(user)
                .build());
        return scenarioRepository.save(Scenario.builder()
                .application(app)
                .name("other-scenario-" + UUID.randomUUID())
                .status(ScenarioStatus.ACTIVE)
                .createdBy(user)
                .build());
    }

    @Test
    void save_persistsAllFieldsIncludingOrderColumn() {
        Step saved = save(scenario, "step-1", 1);

        Step reloaded = stepRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getOrder()).isEqualTo(1);
        assertThat(reloaded.getMethod()).isEqualTo(HttpMethod.GET);
        assertThat(reloaded.getScenario().getId()).isEqualTo(scenario.getId());
    }

    @Test
    void findByScenarioId_ordersByStepOrderAscending() {
        Step third = save(scenario, "c", 3);
        Step first = save(scenario, "a", 1);
        Step second = save(scenario, "b", 2);

        List<Step> result = stepRepository.findByScenarioId(scenario.getId(), ORDER_ASC);

        assertThat(result).extracting(Step::getId).containsExactly(first.getId(), second.getId(), third.getId());
    }

    @Test
    void findByScenarioId_tiesBrokenDeterministically_whenOrderIsDuplicated() {
        // Aucune contrainte d'unicite sur (scenario_id, step_order) - voir
        // Phase 8, choix deliberee pour un futur reordonnancement. "id" est
        // un UUID ALEATOIRE (GenerationType.UUID) : le tri SQL par id ASC en
        // cas d'egalite ne correspond ni a l'ordre d'insertion, NI
        // necessairement a l'ordre naturel Java (UUID.compareTo() compare
        // des long SIGNES, alors qu'H2/SQL comparent les octets du UUID -
        // ces deux ordres peuvent diverger). La SEULE garantie reellement
        // recherchee et testable ici est la STABILITE : la meme requete
        // renvoie toujours le meme ordre, jamais un ordre different d'un
        // appel a l'autre (voir StepServiceImpl, doc du choix ORDER_ASC).
        save(scenario, "same-order-1", 1);
        save(scenario, "same-order-2", 1);

        List<Step> firstCall = stepRepository.findByScenarioId(scenario.getId(), ORDER_ASC);
        List<Step> secondCall = stepRepository.findByScenarioId(scenario.getId(), ORDER_ASC);

        assertThat(firstCall).extracting(Step::getId)
                .containsExactlyElementsOf(secondCall.stream().map(Step::getId).toList());
    }

    @Test
    void findByScenarioId_doesNotReturnOtherScenariosSteps() throws Exception {
        Scenario other = otherScenario();
        Step matching = save(scenario, "mine", 1);
        save(other, "not-mine", 1);

        List<Step> result = stepRepository.findByScenarioId(scenario.getId(), ORDER_ASC);

        assertThat(result).extracting(Step::getId).containsExactly(matching.getId());
    }

    @Test
    void findByScenarioId_scenarioWithoutSteps_returnsEmptyList() throws Exception {
        Scenario emptyScenario = otherScenario();

        assertThat(stepRepository.findByScenarioId(emptyScenario.getId(), ORDER_ASC)).isEmpty();
    }

    @Test
    void existsByScenarioId_reflectsPresenceOfSteps() throws Exception {
        Scenario freshScenario = otherScenario();
        assertThat(stepRepository.existsByScenarioId(freshScenario.getId())).isFalse();

        save(freshScenario, "step", 1);

        assertThat(stepRepository.existsByScenarioId(freshScenario.getId())).isTrue();
    }
}
