package com.loadpilot.backend.service;

import com.loadpilot.backend.dto.response.MetricResponse;
import java.util.List;
import java.util.UUID;

/** Metriques (lecture seule) : une metrique = une requete d'une execution terminee. */
public interface MetricService {

    List<MetricResponse> getAll();

    MetricResponse getById(UUID id);

    List<MetricResponse> getByApplication(UUID applicationId);

    List<MetricResponse> getByScenario(UUID scenarioId);

    List<MetricResponse> getByStep(UUID stepId);

    List<MetricResponse> getByExecution(UUID executionId);

    /** Filtres combinables ; un parametre null est ignore. 404 si un id ne correspond a aucune ressource. */
    List<MetricResponse> search(UUID applicationId, UUID scenarioId, UUID stepId, UUID executionId);
}
