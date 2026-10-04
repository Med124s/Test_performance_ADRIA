package com.loadpilot.backend.service;

import com.loadpilot.backend.dto.request.ScenarioRequest;
import com.loadpilot.backend.dto.response.ScenarioResponse;
import com.loadpilot.backend.security.CurrentUser;
import java.util.List;
import java.util.UUID;

public interface ScenarioService {

    ScenarioResponse create(ScenarioRequest request, CurrentUser currentUser);

    ScenarioResponse getById(UUID id);

    /** Scenarios visibles, ou la vue "archives" si {@code archived}. */
    List<ScenarioResponse> list(boolean archived);

    /** Leve ResourceNotFoundException si l'application n'existe pas. */
    List<ScenarioResponse> listByApplication(UUID applicationId, boolean archived);

    /** Page filtree et triee cote serveur (status : ACTIVE, INACTIVE ou DRAFT ; sort : createdAt|name|updatedAt,asc|desc). */
    com.loadpilot.backend.dto.response.PagedResponse<ScenarioResponse> page(int page, int size, String sort, String search,
            String status, UUID applicationId, boolean archived);

    ScenarioResponse update(UUID id, ScenarioRequest request);

    void delete(UUID id);

    /** Brouillon -> ACTIVE apres validation complete (etapes, CSV, charge). */
    ScenarioResponse publish(UUID id);

    /** Suppression d'un brouillon (DRAFT uniquement), ouverte a SUPER_ADMIN et PERFORMANCE_ENGINEER. */
    void deleteDraft(UUID id);

    /** Informations + etapes (creees / modifiees / supprimees / reordonnees) en une transaction. */
    com.loadpilot.backend.dto.response.ScenarioFullResponse updateFull(UUID id, com.loadpilot.backend.dto.request.ScenarioFullRequest request);
}
