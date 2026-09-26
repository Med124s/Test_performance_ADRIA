package com.loadpilot.backend.service;

import com.loadpilot.backend.dto.request.ApplicationRequest;
import com.loadpilot.backend.dto.response.ApplicationResponse;
import com.loadpilot.backend.dto.response.ApplicationTestResponse;
import com.loadpilot.backend.security.CurrentUser;
import java.util.List;
import java.util.UUID;

public interface ApplicationService {

    ApplicationResponse create(ApplicationRequest request, CurrentUser currentUser);

    ApplicationResponse getById(UUID id);

    List<ApplicationResponse> list();

    ApplicationResponse update(UUID id, ApplicationRequest request);

    void delete(UUID id);

    /** Envoie une vraie requete HTTP vers l'URL de l'application et met a jour son statut. */
    ApplicationTestResponse testAvailability(UUID id);
}
