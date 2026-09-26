package com.loadpilot.backend.service;

import com.loadpilot.backend.dto.request.ScheduledExecutionRequest;
import com.loadpilot.backend.dto.response.ScheduledExecutionResponse;
import java.util.List;
import java.util.UUID;

public interface ScheduledExecutionService {

    ScheduledExecutionResponse create(ScheduledExecutionRequest request, UUID createdByAppUserId);

    /** @throws com.loadpilot.backend.exception.ResourceNotFoundException si absente. */
    ScheduledExecutionResponse update(UUID id, ScheduledExecutionRequest request);

    /** ?scenarioId= filtre optionnel (meme convention que Scenarios/Executions). */
    List<ScheduledExecutionResponse> list(UUID scenarioId);

    ScheduledExecutionResponse getById(UUID id);

    ScheduledExecutionResponse setEnabled(UUID id, boolean enabled);

    /**
     * Declenche IMMEDIATEMENT (voir ScheduledExecutionTransactionHelper -
     * meme protection anti double-declenchement, meme moteur reel via
     * ExecutionService.executeScheduled) - jamais un chemin distinct du
     * declenchement automatique.
     * @throws com.loadpilot.backend.exception.ExecutionLimitExceededException si la capacite globale est depassee (propagee telle quelle, jamais absorbee).
     */
    ScheduledExecutionResponse runNow(UUID id);

    void delete(UUID id);
}
