package com.loadpilot.backend.service;

import com.loadpilot.backend.dto.response.ApplicationDashboardResponse;
import com.loadpilot.backend.dto.response.DashboardResponse;
import java.time.Instant;
import java.util.UUID;

/**
 * Lecture SEULE - aucun stockage dedie (voir Phase 11) : toutes les
 * statistiques sont calculees en temps reel a partir des tables existantes.
 */
public interface DashboardService {

    /** Equivalent a getGlobalDashboard(null, null) - comportement identique a avant P1-C. */
    DashboardResponse getGlobalDashboard();

    /**
     * P1-C — vue globale filtree sur ["from", "to"] (bornes incluses, sur
     * Execution.startedAt/Metric.timestamp) : "from"/"to" chacun optionnel
     * (null = pas de borne de ce cote) - null/null se comporte exactement
     * comme getGlobalDashboard() (tout l'historique, aucune regression).
     * Seuls "executions"/"performance"/"topScenarios" sont filtres ;
     * "applications"/"scenarios" restent all-time (voir DashboardResponse).
     */
    DashboardResponse getGlobalDashboard(Instant from, Instant to);

    /** @throws com.loadpilot.backend.exception.ResourceNotFoundException si l'Application n'existe pas. */
    ApplicationDashboardResponse getApplicationDashboard(UUID applicationId);
}
