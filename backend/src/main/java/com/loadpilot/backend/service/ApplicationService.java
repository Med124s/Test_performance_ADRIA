package com.loadpilot.backend.service;

import com.loadpilot.backend.dto.response.PagedResponse;
import com.loadpilot.backend.dto.request.ApplicationRequest;
import com.loadpilot.backend.dto.response.ApplicationResponse;
import com.loadpilot.backend.dto.response.ApplicationTestResponse;
import com.loadpilot.backend.security.CurrentUser;
import java.util.List;
import java.util.UUID;

public interface ApplicationService {

    ApplicationResponse create(ApplicationRequest request, CurrentUser currentUser);

    ApplicationResponse getById(UUID id);

    /** Liste complete (format historique, sans pagination) : applications
     * non archivees, ou uniquement les archivees si {@code archived}. */
    List<ApplicationResponse> list(boolean archived);

    /** Liste paginee, filtree et triee cote serveur. */
    PagedResponse<ApplicationResponse> page(int page, int size, String sort, String search, String status, boolean archived);

    ApplicationResponse update(UUID id, ApplicationRequest request);

    void delete(UUID id);

    /** Envoie une vraie requete HTTP vers l'URL de l'application et met a jour son statut. */
    ApplicationTestResponse testAvailability(UUID id);

    /** « Tester le monitoring » avec la configuration saisie (rien n'est enregistre). */
    com.loadpilot.backend.dto.response.MonitoringTestResponse testMonitoring(com.loadpilot.backend.dto.request.MonitoringTestRequest request);
}
