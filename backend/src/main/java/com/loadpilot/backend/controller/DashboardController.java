package com.loadpilot.backend.controller;

import com.loadpilot.backend.dto.response.ApplicationDashboardResponse;
import com.loadpilot.backend.dto.response.DashboardResponse;
import com.loadpilot.backend.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/dashboard - lecture seule, ouverte a tout utilisateur authentifie
 * quel que soit son role (couvert par SecurityConfig :
 * anyRequest().authenticated()) - pas de @PreAuthorize necessaire, aucun
 * endpoint de creation/modification/suppression n'existe (voir Phase 11).
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard", description = "Statistiques agregees en temps reel (Applications/Scenarios/Executions/Performance) - lecture seule")
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * P1-C — "from"/"to" optionnels (ISO-8601) : sans eux, comportement
     * identique a avant P1-C (tout l'historique). Le frontend calcule les
     * bornes des presets 24h/7j/30j (voir Dashboard.tsx) - aucun preset
     * cote backend (memes conventions que dateFrom/dateTo sur
     * /api/executions/history, voir ExecutionController).
     */
    @GetMapping
    @Operation(summary = "Vue globale de la plateforme", description = "Agregats calcules a la volee depuis les tables existantes, aucun stockage dedie. ?from=&to= filtrent optionnellement executions/performance/topScenarios sur une plage temporelle (bornes incluses, sur startedAt/timestamp).")
    public DashboardResponse getGlobalDashboard(
            @Parameter(description = "Borne de debut (incluse)") @RequestParam(required = false) Instant from,
            @Parameter(description = "Borne de fin (incluse)") @RequestParam(required = false) Instant to) {
        return dashboardService.getGlobalDashboard(from, to);
    }

    @GetMapping("/applications/{id}")
    @Operation(summary = "Vue detaillee d'une application", description = "Memes statistiques que la vue globale, filtrees pour cette application.")
    @ApiResponse(responseCode = "404", description = "Application introuvable")
    public ApplicationDashboardResponse getApplicationDashboard(@PathVariable UUID id) {
        return dashboardService.getApplicationDashboard(id);
    }
}
