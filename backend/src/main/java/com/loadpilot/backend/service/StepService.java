package com.loadpilot.backend.service;

import com.loadpilot.backend.dto.request.StepRequest;
import com.loadpilot.backend.dto.request.StepTestBatchRequest;
import com.loadpilot.backend.dto.response.StepResponse;
import com.loadpilot.backend.dto.response.StepTestResultResponse;
import java.util.List;
import java.util.UUID;

public interface StepService {

    StepResponse create(StepRequest request);

    StepResponse getById(UUID id);

    List<StepResponse> list();

    /** Leve ResourceNotFoundException si le scenario n'existe pas. */
    List<StepResponse> listByScenario(UUID scenarioId);

    StepResponse update(UUID id, StepRequest request);

    void delete(UUID id);

    /** "Tester les etapes selectionnees" (passage produit reel, 2026-09-30)
     * - envoie une VRAIE requete par etape, jamais une Execution (aucune
     * ligne Execution/ExecutionStepResult creee). Leve ConflictException si
     * les etapes n'appartiennent pas toutes au meme Scenario. */
    List<StepTestResultResponse> testBatch(StepTestBatchRequest request);
}
