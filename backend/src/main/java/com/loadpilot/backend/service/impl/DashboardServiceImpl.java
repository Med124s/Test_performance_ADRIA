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

/** Ancien tableau de bord global, calcule par requetes d'agregation. */
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private final ApplicationRepository applicationRepository;
    private final ScenarioRepository scenarioRepository;
    private final ExecutionRepository executionRepository;
    private final ExecutionStepResultRepository executionStepResultRepository;
    private final PerformanceAggregates performanceAggregates;

    /** Nombre de scenarios du classement "Top scenarios". */
    private static final int TOP_SCENARIOS_LIMIT = 5;

    @Override
    @Transactional(readOnly = true)
    public DashboardResponse getGlobalDashboard() {
        return getGlobalDashboard(null, null);
    }

    @Override
    @Transactional(readOnly = true)
    public DashboardResponse getGlobalDashboard(Instant from, Instant to) {
        // Applications et scenarios : toujours sur tout l'historique.
        ApplicationsSummaryResponse applications = new ApplicationsSummaryResponse(
                applicationRepository.count(),
                applicationRepository.countByStatus(ApplicationStatus.CONNECTED),
                applicationRepository.countByStatus(ApplicationStatus.FAILED),
                applicationRepository.countByStatus(ApplicationStatus.ERROR));

        ScenariosSummaryResponse scenarios = new ScenariosSummaryResponse(
                scenarioRepository.count(),
                scenarioRepository.countByStatus(ScenarioStatus.ACTIVE),
                scenarioRepository.countByStatus(ScenarioStatus.DRAFT));

        boolean rangeApplied = from != null || to != null;
        Instant effectiveFrom = from != null ? from : Instant.EPOCH;
        Instant effectiveTo = to != null ? to : Instant.now();

        ExecutionsSummaryResponse executions = rangeApplied
                ? buildExecutionsSummary(
                        executionRepository.countByStartedAtBetween(effectiveFrom, effectiveTo),
                        executionRepository.countByStatusAndStartedAtBetween(ExecutionStatus.SUCCESS, effectiveFrom, effectiveTo),
                        // "Seuils depasses" compte parmi les executions non reussies.
                        executionRepository.countByStatusAndStartedAtBetween(ExecutionStatus.FAILED, effectiveFrom, effectiveTo)
                                + executionRepository.countByStatusAndStartedAtBetween(ExecutionStatus.THRESHOLDS_EXCEEDED, effectiveFrom, effectiveTo),
                        executionRepository.countByStatusAndStartedAtBetween(ExecutionStatus.RUNNING, effectiveFrom, effectiveTo)
                                + executionRepository.countByStatusAndStartedAtBetween(ExecutionStatus.PAUSED, effectiveFrom, effectiveTo),
                        executionRepository.countByStatusAndStartedAtBetween(ExecutionStatus.CANCELLED, effectiveFrom, effectiveTo),
                        executionStepResultRepository.countByTimestampBetween(effectiveFrom, effectiveTo),
                        executionStepResultRepository.countBySuccessAndTimestampBetween(true, effectiveFrom, effectiveTo),
                        executionStepResultRepository.countBySuccessAndTimestampBetween(false, effectiveFrom, effectiveTo))
                : buildExecutionsSummary(
                        executionRepository.count(),
                        executionRepository.countByStatus(ExecutionStatus.SUCCESS),
                        executionRepository.countByStatus(ExecutionStatus.FAILED) + executionRepository.countByStatus(ExecutionStatus.THRESHOLDS_EXCEEDED),
                        executionRepository.countByStatus(ExecutionStatus.RUNNING) + executionRepository.countByStatus(ExecutionStatus.PAUSED),
                        executionRepository.countByStatus(ExecutionStatus.CANCELLED),
                        executionStepResultRepository.count(),
                        executionStepResultRepository.countBySuccess(true),
                        executionStepResultRepository.countBySuccess(false));

        PerformanceSummaryResponse performance = rangeApplied
                ? buildPerformanceSummary(performanceAggregates.between(effectiveFrom, effectiveTo))
                : buildPerformanceSummary(performanceAggregates.overall());

        List<TopScenarioResponse> topScenarios = buildTopScenarios(effectiveFrom, effectiveTo);

        return new DashboardResponse(applications, scenarios, executions, performance, from, to, topScenarios);
    }

    /** Classement "Top scenarios" par nombre d'executions sur la periode. */
    private List<TopScenarioResponse> buildTopScenarios(Instant from, Instant to) {
        List<ScenarioExecutionCountProjection> topByCount = executionRepository.findTopScenariosByExecutionCount(
                from, to, ExecutionStatus.SUCCESS, PageRequest.of(0, TOP_SCENARIOS_LIMIT));
        if (topByCount.isEmpty()) {
            return List.of();
        }

        List<UUID> scenarioIds = topByCount.stream().map(ScenarioExecutionCountProjection::getScenarioId).toList();
        Map<UUID, Double> avgResponseTimeByScenario = new HashMap<>();
        for (ScenarioAverageResponseTimeProjection row : executionStepResultRepository.averageResponseTimeByScenarioIn(PerformanceAggregates.ACTIVE, scenarioIds, from, to)) {
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
                scenarioRepository.countByApplicationIdAndStatus(applicationId, ScenarioStatus.DRAFT));

        ExecutionsSummaryResponse executions = buildExecutionsSummary(
                executionRepository.countByApplicationId(applicationId),
                executionRepository.countByApplicationIdAndStatus(applicationId, ExecutionStatus.SUCCESS),
                executionRepository.countByApplicationIdAndStatus(applicationId, ExecutionStatus.FAILED)
                        + executionRepository.countByApplicationIdAndStatus(applicationId, ExecutionStatus.THRESHOLDS_EXCEEDED),
                executionRepository.countByApplicationIdAndStatus(applicationId, ExecutionStatus.RUNNING)
                        + executionRepository.countByApplicationIdAndStatus(applicationId, ExecutionStatus.PAUSED),
                executionRepository.countByApplicationIdAndStatus(applicationId, ExecutionStatus.CANCELLED),
                executionStepResultRepository.countByApplicationId(applicationId),
                executionStepResultRepository.countByApplicationIdAndSuccess(applicationId, true),
                executionStepResultRepository.countByApplicationIdAndSuccess(applicationId, false));

        PerformanceSummaryResponse performance = buildPerformanceSummary(performanceAggregates.forApplication(applicationId));

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

    /** part / total en %, arrondi a 2 decimales ; null si total == 0. */
    private BigDecimal rate(long part, long total) {
        if (total <= 0) {
            return null;
        }
        return BigDecimal.valueOf(part)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }

    private PerformanceSummaryResponse buildPerformanceSummary(PerformanceAggregates.Averages averages) {
        return buildPerformanceSummary(averages.responseTime(), averages.throughput(), averages.errorRate());
    }

    private PerformanceSummaryResponse buildPerformanceSummary(Double averageResponseTime, Double averageThroughput,
            Double averageErrorRate) {
        return new PerformanceSummaryResponse(
                toBigDecimal(averageResponseTime, 2),
                toBigDecimal(averageThroughput, 3),
                toBigDecimal(averageErrorRate, 2));
    }

    /** null reste null (moyenne d'un ensemble vide). */
    private BigDecimal toBigDecimal(Double value, int scale) {
        if (value == null) {
            return null;
        }
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }
}
