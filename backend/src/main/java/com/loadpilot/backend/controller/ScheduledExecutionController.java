package com.loadpilot.backend.controller;

import com.loadpilot.backend.dto.request.ScheduledExecutionRequest;
import com.loadpilot.backend.dto.response.ScheduledExecutionResponse;
import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.security.CurrentUser;
import com.loadpilot.backend.service.AppUserSyncService;
import com.loadpilot.backend.service.ScheduledExecutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/scheduled-executions — lecture ouverte a tout utilisateur
 * authentifie (voir SecurityConfig, meme convention que /api/executions) ;
 * creation/modification/activation/desactivation/declenchement manuel/
 * suppression reserves a SUPER_ADMIN et PERFORMANCE_ENGINEER - EXACTEMENT
 * la meme matrice de permissions que /api/executions (planifier un
 * lancement n'est pas une action moins sensible que le lancer directement).
 */
@RestController
@RequestMapping("/api/scheduled-executions")
@RequiredArgsConstructor
@Tag(name = "Scheduled Executions", description = "Planification reelle et persistee du lancement d'un Scenario")
public class ScheduledExecutionController {

    private final ScheduledExecutionService scheduledExecutionService;
    private final AppUserSyncService appUserSyncService;

    @GetMapping
    @Operation(summary = "Lister les planifications", description = "Filtrable par scenarioId ; sans ce parametre, retourne toutes les planifications.")
    public List<ScheduledExecutionResponse> list(@RequestParam(required = false) UUID scenarioId) {
        return scheduledExecutionService.list(scenarioId);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Recuperer une planification par id")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Planification introuvable")
    public ScheduledExecutionResponse getById(@PathVariable UUID id) {
        return scheduledExecutionService.getById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Creer une planification", description = "ONE_TIME (runAt futur) ou RECURRING_CRON (expression cron + timezone). Reserve a SUPER_ADMIN et PERFORMANCE_ENGINEER.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Configuration invalide (cron/timezone/runAt)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Scenario introuvable")
    public ScheduledExecutionResponse create(@Valid @RequestBody ScheduledExecutionRequest request,
                                              @AuthenticationPrincipal Jwt jwt,
                                              Authentication authentication) {
        AppUser createdBy = appUserSyncService.sync(CurrentUser.from(jwt, authentication));
        return scheduledExecutionService.create(request, createdBy.getId());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Modifier une planification", description = "Recalcule toujours la prochaine occurrence depuis la nouvelle configuration. Reserve a SUPER_ADMIN et PERFORMANCE_ENGINEER.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Planification ou Scenario introuvable")
    public ScheduledExecutionResponse update(@PathVariable UUID id, @Valid @RequestBody ScheduledExecutionRequest request) {
        return scheduledExecutionService.update(id, request);
    }

    @PatchMapping("/{id}/enable")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Activer une planification")
    public ScheduledExecutionResponse enable(@PathVariable UUID id) {
        return scheduledExecutionService.setEnabled(id, true);
    }

    @PatchMapping("/{id}/disable")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Désactiver une planification", description = "Une planification desactivee n'est plus jamais declenchee automatiquement (mais reste declenchable via run-now).")
    public ScheduledExecutionResponse disable(@PathVariable UUID id) {
        return scheduledExecutionService.setEnabled(id, false);
    }

    @PostMapping("/{id}/run-now")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Déclencher immédiatement", description = "Meme moteur/protections que le declenchement automatique (voir ScheduledExecutionTrigger). Reserve a SUPER_ADMIN et PERFORMANCE_ENGINEER.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Limite de capacite globale atteinte")
    public ScheduledExecutionResponse runNow(@PathVariable UUID id) {
        return scheduledExecutionService.runNow(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Supprimer une planification", description = "N'affecte jamais les Executions deja generees (independantes, jamais supprimees en cascade).")
    public void delete(@PathVariable UUID id) {
        scheduledExecutionService.delete(id);
    }
}
