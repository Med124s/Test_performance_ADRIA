package com.loadpilot.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.entity.Scenario;
import com.loadpilot.backend.enums.ScenarioStatus;
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
 * Verifie ScenarioRepository avec H2 reel - notamment countByApplicationId
 * et countByApplicationIdAndStatus (Phase 11), jamais testes isolement
 * jusqu'ici (seulement via DashboardServiceImplIntegrationTest).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ScenarioRepositoryTest {

    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private ApplicationRepository applicationRepository;
    @Autowired
    private ScenarioRepository scenarioRepository;

    private AppUser user;
    private Application application;

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
    }

    private Scenario save(Application app, String name, ScenarioStatus status) {
        return scenarioRepository.saveAndFlush(Scenario.builder()
                .application(app)
                .name(name)
                .status(status)
                .createdBy(user)
                .build());
    }

    private Application otherApplication() {
        return applicationRepository.save(Application.builder()
                .name("other-app-" + UUID.randomUUID())
                .url("http://localhost:2")
                .createdBy(user)
                .build());
    }

    @Test
    void save_persistsRelationAndDefaultsCorrectly() {
        Scenario saved = save(application, "scenario-1", ScenarioStatus.ACTIVE);

        Scenario reloaded = scenarioRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getApplication().getId()).isEqualTo(application.getId());
        assertThat(reloaded.getStatus()).isEqualTo(ScenarioStatus.ACTIVE);
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    void findByApplicationId_returnsOnlyThatApplicationsScenarios() {
        // NB : pas d'assertion sur l'ORDRE exact ici - "createdAt" est un
        // @CreationTimestamp a la granularite de la milliseconde, et deux
        // sauvegardes rapprochees dans un test peuvent legitimement obtenir
        // le meme instant (NEWEST_FIRST, voir ScenarioServiceImpl, n'a pas
        // de cle de tri secondaire deterministe) - ce test verifie
        // uniquement le FILTRAGE par application, jamais teste isolement
        // au niveau repository jusqu'ici.
        Application other = otherApplication();
        Scenario first = save(application, "a-1", ScenarioStatus.ACTIVE);
        Scenario second = save(application, "a-2", ScenarioStatus.ACTIVE);
        save(other, "b-1", ScenarioStatus.ACTIVE);

        List<Scenario> result = scenarioRepository.findByApplicationId(
                application.getId(), Sort.by(Sort.Direction.DESC, "createdAt"));

        assertThat(result).extracting(Scenario::getId).containsExactlyInAnyOrder(first.getId(), second.getId());
    }

    @Test
    void existsByApplicationId_reflectsPresenceOfScenarios() {
        Application freshApp = otherApplication();
        assertThat(scenarioRepository.existsByApplicationId(freshApp.getId())).isFalse();

        save(freshApp, "s", ScenarioStatus.ACTIVE);

        assertThat(scenarioRepository.existsByApplicationId(freshApp.getId())).isTrue();
    }

    @Test
    void countByApplicationId_countsExactlyScenariosOfThatApplication() {
        Application other = otherApplication();
        save(application, "a-1", ScenarioStatus.ACTIVE);
        save(application, "a-2", ScenarioStatus.INACTIVE);
        save(other, "b-1", ScenarioStatus.ACTIVE);

        assertThat(scenarioRepository.countByApplicationId(application.getId())).isEqualTo(2L);
        assertThat(scenarioRepository.countByApplicationId(other.getId())).isEqualTo(1L);
    }

    @Test
    void countByApplicationIdAndStatus_filtersByBothDimensions() {
        save(application, "active-1", ScenarioStatus.ACTIVE);
        save(application, "active-2", ScenarioStatus.ACTIVE);
        save(application, "inactive-1", ScenarioStatus.INACTIVE);

        assertThat(scenarioRepository.countByApplicationIdAndStatus(application.getId(), ScenarioStatus.ACTIVE)).isEqualTo(2L);
        assertThat(scenarioRepository.countByApplicationIdAndStatus(application.getId(), ScenarioStatus.INACTIVE)).isEqualTo(1L);
    }

    @Test
    void countByApplicationId_applicationWithoutScenarios_isZero() {
        Application freshApp = otherApplication();

        assertThat(scenarioRepository.countByApplicationId(freshApp.getId())).isZero();
    }
}
