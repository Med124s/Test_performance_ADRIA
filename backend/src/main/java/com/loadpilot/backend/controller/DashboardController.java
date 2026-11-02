package com.loadpilot.backend.controller;

import com.loadpilot.backend.dto.response.ApplicationDashboardResponse;
import com.loadpilot.backend.dto.response.DashboardOverviewResponse;
import com.loadpilot.backend.dto.response.DashboardResponse;
import com.loadpilot.backend.dto.response.PagedResponse;
import com.loadpilot.backend.dto.response.ScenarioTrendResponse;
import com.loadpilot.backend.service.DashboardService;
import com.loadpilot.backend.service.impl.DashboardOverviewService;
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

/** API du tableau de bord. */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard", description = "Statistiques agregees des applications, scenarios, executions et performances (lecture seule)")
public class DashboardController {

    private final DashboardService dashboardService;
    private final DashboardOverviewService overviewService;

    /** "from" et "to" (ISO-8601) bornent la periode ; sans eux, tout l'historique est pris en compte. */
    @GetMapping
    @Operation(summary = "Vue globale de la plateforme", description = "Agregats calcules a la volee depuis les tables existantes, aucun stockage dedie. ?from=&to= filtrent optionnellement executions/performance/topScenarios sur une plage temporelle (bornes incluses, sur startedAt/timestamp).")
    public DashboardResponse getGlobalDashboard(
            @Parameter(description = "Borne de debut (incluse)") @RequestParam(required = false) Instant from,
            @Parameter(description = "Borne de fin (incluse)") @RequestParam(required = false) Instant to) {
        return dashboardService.getGlobalDashboard(from, to);
    }

    @GetMapping("/overview")
    @Operation(summary = "Dashboard : etat, tendance et points a surveiller",
            description = "Tout le Dashboard en une reponse, filtre par application (optionnelle) et par periode (executions lancees entre from et to, bornes incluses) : P95 de la periode, taux d'erreurs, requetes, executions, badges de statut, points a surveiller, derniere execution par scenario comparee a la precedente, etapes les plus lentes, en cours, recentes, prochaines planifiees.")
    public DashboardOverviewResponse overview(
            @Parameter(description = "Application (optionnelle)") @RequestParam(required = false) UUID applicationId,
            @Parameter(description = "Borne de debut (incluse)") @RequestParam(required = false) Instant from,
            @Parameter(description = "Borne de fin (incluse)") @RequestParam(required = false) Instant to) {
        return overviewService.overview(applicationId, from, to);
    }

    @GetMapping("/latest-by-scenario")
    @Operation(summary = "Derniere execution par scenario (paginee)",
            description = "Une ligne par scenario execute dans la periode : sa derniere execution comparee a la precedente, "
                    + "de la plus recente a la plus ancienne. Memes filtres que /overview ; page (0..), size (10 par defaut, 100 au plus).")
    public PagedResponse<DashboardOverviewResponse.ScenarioRow> latestByScenario(
            @Parameter(description = "Application (optionnelle)") @RequestParam(required = false) UUID applicationId,
            @Parameter(description = "Borne de debut (incluse)") @RequestParam(required = false) Instant from,
            @Parameter(description = "Borne de fin (incluse)") @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return overviewService.latestByScenario(applicationId, from, to, page, size);
    }

    @GetMapping("/scenario-trend")
    @Operation(summary = "Evolution des performances d'un scenario",
            description = "P95, taux d'erreurs, debit et utilisateurs virtuels de chaque execution terminee (reussie ou echouee) du scenario sur la periode, dans l'ordre chronologique.")
    @ApiResponse(responseCode = "404", description = "Scenario introuvable")
    public ScenarioTrendResponse scenarioTrend(
            @RequestParam UUID scenarioId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @Parameter(description = "Etape (nom) : P95 et taux d'erreurs de cette etape dans chaque execution")
            @RequestParam(required = false) String stepName) {
        return overviewService.scenarioTrend(scenarioId, from, to, stepName);
    }

    @GetMapping("/applications/{id}")
    @Operation(summary = "Vue detaillee d'une application", description = "Memes statistiques que la vue globale, filtrees pour cette application.")
    @ApiResponse(responseCode = "404", description = "Application introuvable")
    public ApplicationDashboardResponse getApplicationDashboard(@PathVariable UUID id) {
        return dashboardService.getApplicationDashboard(id);
    }
}
