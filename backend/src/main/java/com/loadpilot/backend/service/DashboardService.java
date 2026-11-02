package com.loadpilot.backend.service;

import com.loadpilot.backend.dto.response.ApplicationDashboardResponse;
import com.loadpilot.backend.dto.response.DashboardResponse;
import java.time.Instant;
import java.util.UUID;

/** Statistiques du tableau de bord, calculees a partir des donnees existantes. */
public interface DashboardService {

    /** Vue globale sur tout l'historique. */
    DashboardResponse getGlobalDashboard();

    /** Vue globale sur la periode ["from", "to"] (bornes incluses, chacune optionnelle). */
    DashboardResponse getGlobalDashboard(Instant from, Instant to);

    /** @throws com.loadpilot.backend.exception.ResourceNotFoundException si l'Application n'existe pas. */
    ApplicationDashboardResponse getApplicationDashboard(UUID applicationId);
}
