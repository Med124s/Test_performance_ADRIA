package com.loadpilot.backend.controller;

import com.loadpilot.backend.dto.request.StepRequest;
import com.loadpilot.backend.dto.response.StepResponse;
import com.loadpilot.backend.service.StepService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/steps - lecture ouverte a tout utilisateur authentifie (voir
 * SecurityConfig : anyRequest().authenticated() couvre deja les GET, aucune
 * annotation supplementaire ici pour eviter de dupliquer la regle) ; POST,
 * PUT et DELETE reserves a SUPER_ADMIN et PERFORMANCE_ENGINEER.
 */
@RestController
@RequestMapping("/api/steps")
@RequiredArgsConstructor
@Tag(name = "Steps", description = "Etapes HTTP (GET/POST/PUT/PATCH/DELETE) composant un Scenario")
public class StepController {

    private final StepService stepService;

    /** ?scenarioId= filtre par scenario (voir Phase 8) - meme endpoint
     * plutot que dupliquer un chemin dedie. */
    @GetMapping
    @Operation(summary = "Lister les etapes", description = "Filtrable par scenarioId ; tri par ordre (step_order ASC, id ASC).")
    @ApiResponse(responseCode = "404", description = "scenarioId fourni mais introuvable")
    public List<StepResponse> list(
            @Parameter(description = "Filtre optionnel : ne retourner que les etapes de ce scenario")
            @RequestParam(required = false) UUID scenarioId) {
        return scenarioId != null ? stepService.listByScenario(scenarioId) : stepService.list();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Recuperer une etape par id")
    @ApiResponse(responseCode = "404", description = "Etape introuvable")
    public StepResponse getById(@PathVariable UUID id) {
        return stepService.getById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Creer une etape", description = "Reserve a SUPER_ADMIN et PERFORMANCE_ENGINEER. La methode HTTP (GET/POST/PUT/PATCH/DELETE) est celle que l'etape enverra reellement lors d'une Execution.")
    @ApiResponse(responseCode = "201", description = "Etape creee")
    @ApiResponse(responseCode = "404", description = "Scenario referencee introuvable")
    public StepResponse create(@Valid @RequestBody StepRequest request) {
        return stepService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Modifier une etape", description = "Reserve a SUPER_ADMIN et PERFORMANCE_ENGINEER.")
    @ApiResponse(responseCode = "404", description = "Etape ou Scenario referencee introuvable")
    public StepResponse update(@PathVariable UUID id, @Valid @RequestBody StepRequest request) {
        return stepService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Supprimer une etape", description = "Reserve a SUPER_ADMIN et PERFORMANCE_ENGINEER.")
    @ApiResponse(responseCode = "204", description = "Etape supprimee")
    public void delete(@PathVariable UUID id) {
        stepService.delete(id);
    }
}
