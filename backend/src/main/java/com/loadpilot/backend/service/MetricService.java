package com.loadpilot.backend.service;

import com.loadpilot.backend.dto.response.MetricResponse;
import java.util.List;
import java.util.UUID;

/**
 * Lecture SEULE. Une « metric » = une requete d'une execution terminee, lue directement dans
 * execution_step_result (plus de copie dans une table metric, voir MetricServiceImpl) : son temps de
 * reponse, son code HTTP, et le debit et le taux d'erreurs de son execution.
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
