package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.dto.response.MetricResponse;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.entity.ExecutionStepResult;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.repository.ApplicationRepository;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.ExecutionStepResultRepository;
import com.loadpilot.backend.repository.ScenarioRepository;
import com.loadpilot.backend.repository.StepRepository;
import com.loadpilot.backend.service.MetricService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * /api/metrics construit a partir des resultats (execution_step_result), sans table metric : meme
 * contrat qu'avant (une ligne par requete d'une execution terminee, identifiant = celui du resultat),
 * memes valeurs (debit et taux d'erreurs de l'execution : voir PerformanceAggregates).
 */
@Service
@RequiredArgsConstructor
public class MetricServiceImpl implements MetricService {

    private final ExecutionStepResultRepository resultRepository;
    private final ApplicationRepository applicationRepository;
    private final ScenarioRepository scenarioRepository;
    private final StepRepository stepRepository;
    private final ExecutionRepository executionRepository;
    /** Une ligne par requete executee : des millions pour un long test (voir ResultLoadGuard). */
    private final ResultLoadGuard resultLoadGuard;

    @Override
    @Transactional(readOnly = true)
    public List<MetricResponse> getAll() {
        return rows(null, null, null, null);
    }

    @Override
    @Transactional(readOnly = true)
    public MetricResponse getById(UUID id) {
        ExecutionStepResult result = resultRepository.findById(id)
                .filter(r -> !PerformanceAggregates.ACTIVE.contains(r.getExecution().getStatus()))
                .orElseThrow(() -> new ResourceNotFoundException("Metric introuvable : " + id));
        return toResponse(result);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MetricResponse> getByApplication(UUID applicationId) {
        requireExists(applicationRepository.existsById(applicationId), "Application", applicationId);
        return rows(applicationId, null, null, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MetricResponse> getByScenario(UUID scenarioId) {
        requireExists(scenarioRepository.existsById(scenarioId), "Scenario", scenarioId);
        return rows(null, scenarioId, null, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MetricResponse> getByStep(UUID stepId) {
        requireExists(stepRepository.existsById(stepId), "Step", stepId);
        return rows(null, null, stepId, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MetricResponse> getByExecution(UUID executionId) {
        requireExists(executionRepository.existsById(executionId), "Execution", executionId);
        return rows(null, null, null, executionId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MetricResponse> search(UUID applicationId, UUID scenarioId, UUID stepId, UUID executionId) {
        if (applicationId != null) {
            requireExists(applicationRepository.existsById(applicationId), "Application", applicationId);
        }
        if (scenarioId != null) {
            requireExists(scenarioRepository.existsById(scenarioId), "Scenario", scenarioId);
        }
        if (stepId != null) {
            requireExists(stepRepository.existsById(stepId), "Step", stepId);
        }
        if (executionId != null) {
            requireExists(executionRepository.existsById(executionId), "Execution", executionId);
        }
        return rows(applicationId, scenarioId, stepId, executionId);
    }

    /** Chargement admis seulement s'il tient dans le budget memoire (COUNT prealable, voir ResultLoadGuard). */
    private List<MetricResponse> rows(UUID applicationId, UUID scenarioId, UUID stepId, UUID executionId) {
        long count = resultRepository.countMetricRows(PerformanceAggregates.ACTIVE, applicationId, scenarioId, stepId, executionId);
        try (ResultLoadGuard.Reservation r = resultLoadGuard.reserve(ResultLoadGuard.Load.METRICS, count)) {
            return resultRepository.searchMetricRows(PerformanceAggregates.ACTIVE, applicationId, scenarioId, stepId, executionId)
                    .stream().map(MetricServiceImpl::toResponse).toList();
        }
    }

    /** Une requete, avec le debit et le taux d'erreurs de son execution (memes formules que l'ancienne metric). */
    static MetricResponse toResponse(ExecutionStepResult result) {
        Execution execution = result.getExecution();
        int failed = execution.getFailedSteps() == null ? 0 : execution.getFailedSteps();
        int requests = (execution.getSuccessfulSteps() == null ? 0 : execution.getSuccessfulSteps()) + failed;
        return new MetricResponse(
                result.getId().toString(),
                execution.getScenario().getApplication() == null ? null : execution.getScenario().getApplication().getId().toString(),
                execution.getScenario().getId().toString(),
                result.getStep() == null ? null : result.getStep().getId().toString(),
                execution.getId().toString(),
                result.getResponseTime() == null ? null : result.getResponseTime().intValue(),
                result.getHttpStatus(),
                PerformanceAggregates.throughput(requests, execution.getDuration(), execution.getPausedDurationMs()),
                PerformanceAggregates.errorRate(failed, requests),
                result.getTimestamp());
    }

    private void requireExists(boolean exists, String label, UUID id) {
        if (!exists) {
            throw new ResourceNotFoundException(label + " introuvable : " + id);
        }
    }
}
