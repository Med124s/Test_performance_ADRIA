

                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    package com.loadpilot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.loadpilot.backend.dto.response.ApplicationDashboardResponse;
import com.loadpilot.backend.dto.response.DashboardResponse;
import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.enums.ApplicationStatus;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.repository.ApplicationRepository;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.ExecutionStepResultRepository;
import com.loadpilot.backend.repository.MetricRepository;
import com.loadpilot.backend.repository.ScenarioRepository;
import com.loadpilot.backend.service.impl.DashboardServiceImpl;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Test UNITAIRE pur (Mockito, aucun Spring, aucun H2) de la logique
 * arithmetique de DashboardServiceImpl : division par zero, moyennes null
 * quand aucune Metric n'existe, arrondi documente. Un test integration/H2
 * ne peut pas verifier deterministement une base "vide" (voir
 * DashboardServiceImplIntegrationTest et DashboardControllerTest - la base
 * H2 est partagee entre classes de test dans le meme fork Surefire) ; ce
 * test-ci controle donc l'arithmetique de maniere totalement isolee et
 * reproductible, en pilotant directement les valeurs renvoyees par les
 * repositories mockes.
 */
@ExtendWith(MockitoExtension.class)
class DashboardServiceImplUnitTest {

    @Mock
    private ApplicationRepository applicationRepository;
    @Mock
    private ScenarioRepository scenarioRepository;
    @Mock
    private ExecutionRepository executionRepository;
    @Mock
    private ExecutionStepResultRepository executionStepResultRepository;
    @Mock
    private MetricRepository metricRepository;

    @InjectMocks
    private DashboardServiceImpl dashboardService;

    @Test
    void getGlobalDashboard_noData_returnsZeroCountsNullRatesAndNullPerformance() {
        // Mockito renvoie 0 pour tout long/int non stubbe, MAIS renvoie 0.0
        // (pas null) pour un Double non stubbe (comportement par defaut de
        // Mockito pour les types numeriques boites, meme non-primitifs) - il
        // faut donc stubber explicitement null pour simuler un AVG() JPQL
        // sur un ensemble vide (aucune Metric).
        when(metricRepository.averageResponseTime()).thenReturn(null);
        when(metricRepository.averageThroughput()).thenReturn(null);
        when(metricRepository.averageErrorRate()).thenReturn(null);

        DashboardResponse response = dashboardService.getGlobalDashboard();

        assertThat(response.applications().totalApplications()).isZero();
        assertThat(response.applications().connectedApplications()).isZero();
        assertThat(response.scenarios().totalScenarios()).isZero();
        assertThat(response.executions().totalExecutions()).isZero();
        assertThat(response.executions().totalStepsExecuted()).isZero();
        assertThat(response.executions().successfulSteps()).isZero();
        assertThat(response.executions().failedSteps()).isZero();

        // Aucune division par zero : le taux est null (indefini), jamais 0 invente.
        assertThat(response.executions().successRate()).isNull();
        assertThat(response.executions().failureRate()).isNull();

        // AVG() sur un ensemble vide -> null propage tel quel, jamais 0 invente.
        assertThat(response.performance().averageResponseTime()).isNull();
        assertThat(response.performance().averageThroughput()).isNull();
        assertThat(response.performance().averageErrorRate()).isNull();
    }

    @Test
    void getGlobalDashboard_stepsExecuted_computesSuccessAndFailureRates() {
        when(executionStepResultRepository.count()).thenReturn(10L);
        when(executionStepResultRepository.countBySuccess(true)).thenReturn(7L);
        when(executionStepResultRepository.countBySuccess(false)).thenReturn(3L);

        DashboardResponse response = dashboardService.getGlobalDashboard();

        assertThat(response.executions().totalStepsExecuted()).isEqualTo(10L);
        assertThat(response.executions().successfulSteps()).isEqualTo(7L);
        assertThat(response.executions().failedSteps()).isEqualTo(3L);
        assertThat(response.executions().successRate()).isEqualByComparingTo("70.00");
        assertThat(response.executions().failureRate()).isEqualByComparingTo("30.00");
    }

    @Test
    void getGlobalDashboard_metricsPresent_roundsAveragesToDocumentedScale() {
        when(metricRepository.averageResponseTime()).thenReturn(123.456);
        when(metricRepository.averageThroughput()).thenReturn(2.6666);
        when(metricRepository.averageErrorRate()).thenReturn(12.5);

        DashboardResponse response = dashboardService.getGlobalDashboard();

        // averageResponseTime : ms, 2 decimales.
        assertThat(response.performance().averageResponseTime()).isEqualByComparingTo("123.46");
        // averageThroughput : requetes/sec, 3 decimales.
        assertThat(response.performance().averageThroughput()).isEqualByComparingTo("2.667");
        // averageErrorRate : %, 2 decimales.
        assertThat(response.performance().averageErrorRate()).isEqualByComparingTo("12.50");
    }

    @Test
    void getGlobalDashboard_applicationCounts_reflectRepositoryValues() {
        when(applicationRepository.count()).thenReturn(5L);
        when(applicationRepository.countByStatus(ApplicationStatus.CONNECTED)).thenReturn(3L);
        when(applicationRepository.countByStatus(ApplicationStatus.FAILED)).thenReturn(1L);
        when(applicationRepository.countByStatus(ApplicationStatus.ERROR)).thenReturn(1L);

        DashboardResponse response = dashboardService.getGlobalDashboard();

        assertThat(response.applications().totalApplications()).isEqualTo(5L);
        assertThat(response.applications().connectedApplications()).isEqualTo(3L);
        assertThat(response.applications().failedApplications()).isEqualTo(1L);
        assertThat(response.applications().errorApplications()).isEqualTo(1L);
    }

    @Test
    void getApplicationDashboard_nonexistentApplication_throwsResourceNotFound() {
        UUID id = UUID.randomUUID();
        when(applicationRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dashboardService.getApplicationDashboard(id))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getApplicationDashboard_existingApplication_buildsIdentityFromEntityWithoutExposingIt() {
        UUID id = UUID.randomUUID();
        Application application = Application.builder()
                .id(id)
                .name("my-app")
                .status(ApplicationStatus.CONNECTED)
                .build();
        when(applicationRepository.findById(id)).thenReturn(Optional.of(application));
        // Double non stubbe -> Mockito renvoie 0.0 par defaut (pas null) :
        // stub explicite pour simuler l'absence reelle de Metric.
        when(metricRepository.averageResponseTimeByApplication(id)).thenReturn(null);

        ApplicationDashboardResponse response = dashboardService.getApplicationDashboard(id);

        assertThat(response.application().id()).isEqualTo(id.toString());
        assertThat(response.application().name()).isEqualTo("my-app");
        assertThat(response.application().status()).isEqualTo(ApplicationStatus.CONNECTED);
        // Aucune Execution/Metric mockee pour cet id -> zero/null partout, sans exception.
        assertThat(response.executions().totalExecutions()).isZero();
        assertThat(response.executions().successRate()).isNull();
        assertThat(response.performance().averageResponseTime()).isNull();
    }

    @Test
    void getApplicationDashboard_applicationWithNullStatus_exposesNullStatus() {
        UUID id = UUID.randomUUID();
        Application application = Application.builder().id(id).name("never-tested").status(null).build();
        when(applicationRepository.findById(any())).thenReturn(Optional.of(application));

        ApplicationDashboardResponse response = dashboardService.getApplicationDashboard(id);

        assertThat(response.application().status()).isNull();
    }
}
