package com.loadpilot.backend.service;

import com.loadpilot.backend.dto.request.StepRequest;
import com.loadpilot.backend.dto.response.StepResponse;
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
}
