package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.dto.response.MetricResponse;
import com.loadpilot.backend.entity.Metric;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.mapper.MetricMapper;
import com.loadpilot.backend.repository.ApplicationRepository;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.MetricRepository;
import com.loadpilot.backend.repository.ScenarioRepository;
import com.loadpilot.backend.repository.StepRepository;
import com.loadpilot.backend.service.MetricService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MetricServiceImpl implements MetricService {

    private static final Sort TIMESTAMP_ASC = Sort.by(Sort.Direction.ASC, "timestamp");

    private final MetricRepository metricRepository;
    private final ApplicationRepository applicationRepository;
    private final ScenarioRepository scenarioRepository;
    private final StepRepository stepRepository;
    private final ExecutionRepository executionRepository;
    private final MetricMapper metricMapper;

    @Override
    @Transactional(readOnly = true)
    public List<MetricResponse> getAll() {
        return metricMapper.toResponseList(metricRepository.findAll(TIMESTAMP_ASC));
    }

    @Override
    @Transactional(readOnly = true)
    public MetricResponse getById(UUID id) {
        Metric metric = metricRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Metric introuvable : " + id));
        return metricMapper.toResponse(metric);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MetricResponse> getByApplication(UUID applicationId) {
        requireExists(applicationRepository.existsById(applicationId), "Application", applicationId);
        return metricMapper.toResponseList(metricRepository.findByApplicationId(applicationId, TIMESTAMP_ASC));
    }

    @Override
    @Transactional(readOnly = true)
    public List<MetricResponse> getByScenario(UUID scenarioId) {
        requireExists(scenarioRepository.existsById(scenarioId), "Scenario", scenarioId);
        return metricMapper.toResponseList(metricRepository.findByScenarioId(scenarioId, TIMESTAMP_ASC));
    }

    @Override
    @Transactional(readOnly = true)
    public List<MetricResponse> getByStep(UUID stepId) {
        requireExists(stepRepository.existsById(stepId), "Step", stepId);
        return metricMapper.toResponseList(metricRepository.findByStepId(stepId, TIMESTAMP_ASC));
    }

    @Override
    @Transactional(readOnly = true)
    public List<MetricResponse> getByExecution(UUID executionId) {
        requireExists(executionRepository.existsById(executionId), "Execution", executionId);
        return metricMapper.toResponseList(metricRepository.findByExecutionId(executionId, TIMESTAMP_ASC));
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
        return metricMapper.toResponseList(
                metricRepository.search(applicationId, scenarioId, stepId, executionId, TIMESTAMP_ASC));
    }

    private void requireExists(boolean exists, String label, UUID id) {
        if (!exists) {
            throw new ResourceNotFoundException(label + " introuvable : " + id);
        }
    }
}
