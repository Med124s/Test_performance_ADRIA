package com.loadpilot.backend.service;

import com.loadpilot.backend.dto.response.MetricResponse;
import java.util.List;
import java.util.UUID;

/**
 * Lecture SEULE - aucune creation/modification/suppression manuelle de
 * Metric (voir Phase 10 : la seule source de Metric est la generation
 * automatique apres une Execution reelle, voir
 * service.impl.MetricGenerationService).
 */
public interface MetricService {

    List<MetricResponse> getAll();

    MetricResponse getById(UUID id);

    List<MetricResponse> getByApplication(UUID applicationId);

    List<MetricResponse> getByScenario(UUID scenarioId);

    List<MetricResponse> getByStep(UUID stepId);

    List<MetricResponse> getByExecution(UUID executionId);

    /** Filtre combinable (logique ET) - un parametre null est ignore. Toute
     * id fournie qui ne correspond a aucune ressource existante declenche
     * un ResourceNotFoundException (404). */
    List<MetricResponse> search(UUID applicationId, UUID scenarioId, UUID stepId, UUID executionId);
}
