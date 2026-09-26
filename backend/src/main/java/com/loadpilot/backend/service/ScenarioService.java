package com.loadpilot.backend.service;

import com.loadpilot.backend.dto.request.ScenarioRequest;
import com.loadpilot.backend.dto.response.ScenarioResponse;
import com.loadpilot.backend.security.CurrentUser;
import java.util.List;
import java.util.UUID;

public interface ScenarioService {

    ScenarioResponse create(ScenarioRequest request, CurrentUser currentUser);

    ScenarioResponse getById(UUID id);

    List<ScenarioResponse> list();

    /** Leve ResourceNotFoundException si l'application n'existe pas. */
    List<ScenarioResponse> listByApplication(UUID applicationId);

    ScenarioResponse update(UUID id, ScenarioRequest request);

    void delete(UUID id);
}
