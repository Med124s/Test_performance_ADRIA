package com.loadpilot.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.enums.ApplicationStatus;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifie ApplicationRepository avec H2 reel (voir application-test.yml) -
 * en particulier countByStatus (Phase 11), jamais teste isolement jusqu'ici
 * (seulement indirectement via DashboardServiceImplIntegrationTest).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ApplicationRepositoryTest {

    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private ApplicationRepository applicationRepository;

    private AppUser user;

    @BeforeEach
    void setUp() {
        user = appUserRepository.save(AppUser.builder()
                .keycloakSubject(UUID.randomUUID().toString())
                .username("tester")
                .enabled(true)
                .build());
    }

    private Application save(String name, ApplicationStatus status) {
        return applicationRepository.saveAndFlush(Application.builder()
                .name(name)
                .url("http://localhost:1")
                .status(status)
                .createdBy(user)
                .build());
    }

    @Test
    void save_persistsGeneratedUuidAndAllFields() {
        Application saved = save("app-" + UUID.randomUUID(), ApplicationStatus.CONNECTED);

        assertThat(saved.getId()).isNotNull();
        Application reloaded = applicationRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ApplicationStatus.CONNECTED);
        assertThat(reloaded.getCreatedBy().getId()).isEqualTo(user.getId());
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void save_withoutStatus_isAllowed() {
        // Aucun test de disponibilite encore lance - status null (voir
        // ApplicationStatus, jamais de valeur devinee).
        Application saved = save("never-tested-" + UUID.randomUUID(), null);

        assertThat(applicationRepository.findById(saved.getId()).orElseThrow().getStatus()).isNull();
    }

    @Test
    void countByStatus_countsOnlyMatchingStatus_deltaAcrossThreeStatuses() {
        // Comparaison delta avant/apres (base H2 partagee entre classes de
        // test dans le meme fork Surefire, voir MetricRepositoryTest) plutot
        // qu'un compte absolu - fiable independamment de l'ordre d'execution.
        long connectedBefore = applicationRepository.countByStatus(ApplicationStatus.CONNECTED);
        long failedBefore = applicationRepository.countByStatus(ApplicationStatus.FAILED);
        long errorBefore = applicationRepository.countByStatus(ApplicationStatus.ERROR);

        save("connected-1-" + UUID.randomUUID(), ApplicationStatus.CONNECTED);
        save("connected-2-" + UUID.randomUUID(), ApplicationStatus.CONNECTED);
        save("failed-" + UUID.randomUUID(), ApplicationStatus.FAILED);

        assertThat(applicationRepository.countByStatus(ApplicationStatus.CONNECTED)).isEqualTo(connectedBefore + 2);
        assertThat(applicationRepository.countByStatus(ApplicationStatus.FAILED)).isEqualTo(failedBefore + 1);
        // ERROR non touche par ce test - doit rester strictement inchange.
        assertThat(applicationRepository.countByStatus(ApplicationStatus.ERROR)).isEqualTo(errorBefore);
    }

    @Test
    void countByStatus_applicationWithNullStatus_isNeverCountedInAnyStatus() {
        long connectedBefore = applicationRepository.countByStatus(ApplicationStatus.CONNECTED);
        long failedBefore = applicationRepository.countByStatus(ApplicationStatus.FAILED);
        long errorBefore = applicationRepository.countByStatus(ApplicationStatus.ERROR);

        save("null-status-" + UUID.randomUUID(), null);

        assertThat(applicationRepository.countByStatus(ApplicationStatus.CONNECTED)).isEqualTo(connectedBefore);
        assertThat(applicationRepository.countByStatus(ApplicationStatus.FAILED)).isEqualTo(failedBefore);
        assertThat(applicationRepository.countByStatus(ApplicationStatus.ERROR)).isEqualTo(errorBefore);
    }

    @Test
    void findById_unknownId_returnsEmpty() {
        assertThat(applicationRepository.findById(UUID.randomUUID())).isEmpty();
    }
}
