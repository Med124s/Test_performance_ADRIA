package com.loadpilot.backend.controller;

import com.loadpilot.backend.dto.response.MetricResponse;
import com.loadpilot.backend.service.MetricService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API des metriques (lecture seule). */
@RestController
@RequestMapping("/api/metrics")
@RequiredArgsConstructor
@Tag(name = "Metrics", description = "Mesures par requete des executions terminees (temps de reponse, code HTTP, debit, taux d'erreurs) - lecture seule")
public class MetricController {

    private final MetricService metricService;

    /** Filtres combinables applicationId, scenarioId, stepId et executionId ; sans filtre, toutes les metriques. */
    @GetMapping
    @Operation(summary = "Lister/filtrer les metriques", description = "Filtres combinables en ET ; aucune Metric n'est creable via l'API, elles sont generees par le backend apres chaque Execution.")
    @ApiResponse(responseCode = "404", description = "Un des ids de filtre fourni ne correspond a aucune ressource existante")
    public List<MetricResponse> list(
            @Parameter(description = "Filtre optionnel par application") @RequestParam(required = false) UUID applicationId,
            @Parameter(description = "Filtre optionnel par scenario") @RequestParam(required = false) UUID scenarioId,
            @Parameter(description = "Filtre optionnel par etape") @RequestParam(required = false) UUID stepId,
            @Parameter(description = "Filtre optionnel par execution") @RequestParam(required = false) UUID executionId) {
        if (applicationId == null && scenarioId == null && stepId == null && executionId == null) {
            return metricService.getAll();
        }
        return metricService.search(applicationId, scenarioId, stepId, executionId);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Recuperer une metrique par id")
    @ApiResponse(responseCode = "404", description = "Metrique introuvable")
    public MetricResponse getById(@PathVariable UUID id) {
        return metricService.getById(id);
    }
}
