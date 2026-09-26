package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.dto.response.ApplicationDashboardResponse;
import com.loadpilot.backend.dto.response.ApplicationIdentityResponse;
import com.loadpilot.backend.dto.response.ApplicationsSummaryResponse;
import com.loadpilot.backend.dto.response.DashboardResponse;
import com.loadpilot.backend.dto.response.ExecutionsSummaryResponse;
import com.loadpilot.backend.dto.response.PerformanceSummaryResponse;
import com.loadpilot.backend.dto.response.ScenariosSummaryResponse;
import com.loadpilot.backend.dto.response.TopScenarioResponse;
import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.enums.ApplicationStatus;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.enums.ScenarioStatus;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.repository.ApplicationRepository;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.ExecutionStepResultRepository;
import com.loadpilot.backend.repository.MetricRepository;
import com.loadpilot.backend.repository.ScenarioAverageResponseTimeProjection;
import com.loadpilot.backend.repository.ScenarioExecutionCountProjection;
import com.loadpilot.backend.repository.ScenarioRepository;
import com.loadpilot.backend.service.DashboardService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Toutes les statistiques sont calculees en temps reel a partir des tables
 * existantes (Application/Scenario/Execution/ExecutionStepResult/Metric) via
 * des requetes d'agregation dediees (COUNT/AVG) - jamais de N+1 (aucune
 * collection JPA chargee puis parcourue en Java), aucune donnee inventee.
 * Chaque methode publique est enveloppee dans UNE SEULE transaction en
 * lecture seule pour garantir un instantane coherent entre les differentes
 * requetes d'agregation.
 */
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private final ApplicationRepository applicationRepository;
    private final ScenarioRepository scenarioRepository;
    private final ExecutionRepository executionRepository;
    private final ExecutionStepResultRepository executionStepResultRepository;
    private final MetricRepository metricRepository;

    /** Nombre de scenarios retenus pour le widget "Top scenarios" (voir
     * prompt P1-C, section 9) - une petite constante, jamais une valeur
     * configurable sans besoin demontre (coherent avec la simplicite deja
     * choisie pour d'autres limites fixes du projet). */
    private static final int TOP_SCENARIOS_LIMIT = 5;

    @Override
    @Transactional(readOnly = true)
    public DashboardResponse getGlobalDashboard() {
        return getGlobalDashboard(null, null);
    }

    @Override
    @Transactional(readOnly = true)
    public DashboardResponse getGlobalDashboard(Instant from, Instant to) {
        // "applications"/"scenarios" restent TOUJOURS all-time (voir
        // DashboardResponse) - comportement strictement identique a avant
        // P1-C, jamais touche par "from"/"to".
        ApplicationsSummaryResponse applications = new ApplicationsSummaryResponse(
                applicationRepository.count(),
                applicationRepository.countByStatus(ApplicationStatus.CONNECTED),
                applicationRepository.countByStatus(ApplicationStatus.FAILED),
                applicationRepository.countByStatus(ApplicationStatus.ERROR));

        ScenariosSummaryResponse scenarios = new ScenariosSummaryResponse(
                scenarioRepository.count(),
                scenarioRepository.countByStatus(ScenarioStatus.ACTIVE),
                scenarioRepository.countByStatus(ScenarioStatus.INACTIVE));

        boolean rangeApplied = from != null || to != null;
        Instant effectiveFrom = from != null ? from : Instant.EPOCH;
        Instant effectiveTo = to != null ? to : Instant.now();

        ExecutionsSummaryResponse executions = rangeApplied
                ? buildExecutionsSummary(
                        executionRepository.countByStartedAtBetween(effectiveFrom, effectiveTo),
                        executionRepository.countByStatusAndStartedAtBetween(ExecutionStatus.SUCCESS, effectiveFrom, effectiveTo),
                        executionRepository.countByStatusAndStartedAtBetween(ExecutionStatus.FAILED, effectiveFrom, effectiveTo),
                        executionRepository.countByStatusAndStartedAtBetween(ExecutionStatus.RUNNING, effectiveFrom, effectiveTo),
                        executionRepository.countByStatusAndStartedAtBetween(ExecutionStatus.CANCELLED, effectiveFrom, effectiveTo),
                        executionStepResultRepository.countByTimestampBetween(effectiveFrom, effectiveTo),
                        executionStepResultRepository.countBySuccessAndTimestampBetween(true, effectiveFrom, effectiveTo),
                        executionStepResultRepository.countBySuccessAndTimestampBetween(false, effectiveFrom, effectiveTo))
                : buildExecutionsSummary(
                        executionRepository.count(),
                        executionRepository.countByStatus(ExecutionStatus.SUCCESS),
                        executionRepository.countByStatus(ExecutionStatus.FAILED),
                        executionRepository.countByStatus(ExecutionStatus.RUNNING),
                        executionRepository.countByStatus(ExecutionStatus.CANCELLED),
                        executionStepResultRepository.count(),
                        executionStepResultRepository.countBySuccess(true),
                        executionStepResultRepository.countBySuccess(false));

        PerformanceSummaryResponse performance = rangeApplied
                ? buildPerformanceSummary(
                        metricRepository.averageResponseTimeBetween(effectiveFrom, effectiveTo),
                        metricRepository.averageThroughputBetween(effectiveFrom, effectiveTo),
                        metricRepository.averageErrorRateBetween(effectiveFrom, effectiveTo))
                : buildPerformanceSummary(
                        metricRepository.averageResponseTime(),
                        metricRepository.averageThroughput(),
                        metricRepository.averageErrorRate());

        List<TopScenarioResponse> topScenarios = buildTopScenarios(effectiveFrom, effectiveTo);

        return new DashboardResponse(applications, scenarios, executions, performance, from, to, topScenarios);
    }

    /**
     * P1-C — widget "Top scenarios" : classe par nombre reel d'executions
     * dans la plage (all-time si aucune plage n'a ete demandee - "from"/"to"
     * sont deja resolus a EPOCH/now par l'appelant). Deux requetes
     * d'agregation SQL au total (jamais N+1) : la premiere identifie les
     * TOP_SCENARIOS_LIMIT scenarios et leurs comptes, la seconde recupere
     * leur temps de reponse moyen en une seule requete groupee.
     */
    private List<TopScenarioResponse> buildTopScenarios(Instant from, Instant to) {
        List<ScenarioExecutionCountProjection> topByCount = executionRepository.findTopScenariosByExecutionCount(
                from, to, ExecutionStatus.SUCCESS, PageRequest.of(0, TOP_SCENARIOS_LIMIT));
        if (topByCount.isEmpty()) {
            return List.of();
        }

        List<UUID> scenarioIds = topByCount.stream().map(ScenarioExecutionCountProjection::getScenarioId).toList();
        Map<UUID, Double> avgResponseTimeByScenario = new HashMap<>();
        for (ScenarioAverageResponseTimeProjection row : metricRepository.averageResponseTimeByScenarioIn(scenarioIds, from, to)) {
            avgResponseTimeByScenario.put(row.getScenarioId(), row.getAverageResponseTime());
        }

        return topByCount.stream()
                .map(row -> new TopScenarioResponse(
                        row.getScenarioId().toString(),
                        row.getScenarioName(),
                        row.getApplicationName(),
                        row.getExecutionCount(),
                        rate(row.getSuccessCount(), row.getExecutionCount()),
                        toBigDecimal(avgResponseTimeByScenario.get(row.getScenarioId()), 2)))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ApplicationDashboardResponse getApplicationDashboard(UUID applicationId) {
        Application application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Application introuvable : " + applicationId));

        ApplicationIdentityResponse identity = new ApplicationIdentityResponse(
                application.getId().toString(), application.getName(), application.getStatus());

        ScenariosSummaryResponse scenarios = new ScenariosSummaryResponse(
                scenarioRepository.countByApplicationId(applicationId),
                scenarioRepository.countByApplicationIdAndStatus(applicationId, ScenarioStatus.ACTIVE),
                scenarioRepository.countByApplicationIdAndStatus(applicationId, ScenarioStatus.INACTIVE));

        ExecutionsSummaryResponse executions = buildExecutionsSummary(
                executionRepository.countByApplicationId(applicationId),
                executionRepository.countByApplicationIdAndStatus(applicationId, ExecutionStatus.SUCCESS),
                executionRepository.countByApplicationIdAndStatus(applicationId, ExecutionStatus.FAILED),
                executionRepository.countByApplicationIdAndStatus(applicationId, ExecutionStatus.RUNNING),
                executionRepository.countByApplicationIdAndStatus(applicationId, ExecutionStatus.CANCELLED),
                executionStepResultRepository.countByApplicationId(applicationId),
                executionStepResultRepository.countByApplicationIdAndSuccess(applicationId, true),
                executionStepResultRepository.countByApplicationIdAndSuccess(applicationId, false));

        PerformanceSummaryResponse performance = buildPerformanceSummary(
                metricRepository.averageResponseTimeByApplication(applicationId),
                metricRepository.averageThroughputByApplication(applicationId),
                metricRepository.averageErrorRateByApplication(applicationId));

        return new ApplicationDashboardResponse(identity, scenarios, executions, performance);
    }

    private ExecutionsSummaryResponse buildExecutionsSummary(long totalExecutions, long successfulExecutions,
            long failedExecutions, long runningExecutions, long cancelledExecutions, long totalStepsExecuted,
            long successfulSteps, long failedSteps) {
        return new ExecutionsSummaryResponse(
                totalExecutions, successfulExecutions, failedExecutions, runningExecutions, cancelledExecutions,
                totalStepsExecuted, successfulSteps, failedSteps,
                rate(successfulSteps, totalStepsExecuted),
                rate(failedSteps, totalStepsExecuted));
    }

    /** Pourcentage part/total, arrondi 2 decimales - null si total == 0
     * (aucune division par zero, jamais un taux invente). */
    private BigDecimal rate(long part, long total) {
        if (total <= 0) {
            return null;
        }
        return BigDecimal.valueOf(part)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }

    private PerformanceSummaryResponse buildPerformanceSummary(Double averageResponseTime, Double averageThroughput,
            Double averageErrorRate) {
        return new PerformanceSummaryResponse(
                toBigDecimal(averageResponseTime, 2),
                toBigDecimal(averageThroughput, 3),
                toBigDecimal(averageErrorRate, 2));
    }

    /** AVG() JPQL renvoie null sur un ensemble vide (aucune Metric) - jamais
     * remplace par 0, propage tel quel comme valeur "indefinie". */
    private BigDecimal toBigDecimal(Double value, int scale) {
        if (value == null) {
            return null;
        }
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }
}
