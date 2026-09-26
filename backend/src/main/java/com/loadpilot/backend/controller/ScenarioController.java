package com.loadpilot.backend.controller;

import com.loadpilot.backend.dto.request.ScenarioRequest;
import com.loadpilot.backend.dto.response.ScenarioResponse;
import com.loadpilot.backend.security.CurrentUser;
import com.loadpilot.backend.service.ScenarioService;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
 * /api/scenarios - lecture ouverte a tout utilisateur authentifie (voir
 * SecurityConfig : anyRequest().authenticated() couvre deja les GET, aucune
 * annotation supplementaire ici pour eviter de dupliquer la regle) ; POST,
 * PUT et DELETE reserves a SUPER_ADMIN et PERFORMANCE_ENGINEER.
 */
@RestController
@RequestMapping("/api/scenarios")
@RequiredArgsConstructor
@Tag(name = "Scenarios", description = "Scenarios de test HTTP appartenant a une Application")
public class ScenarioController {

    private final ScenarioService scenarioService;

    /** ?applicationId= filtre par application (voir Phase 7) - meme
     * endpoint plutot que dupliquer un chemin dedie. */
    @GetMapping
    @Operation(summary = "Lister les scenarios", description = "Filtrable par applicationId ; sans ce parametre, retourne tous les scenarios.")
    @ApiResponse(responseCode = "404", description = "applicationId fourni mais introuvable")
    public List<ScenarioResponse> list(
            @Parameter(description = "Filtre optionnel : ne retourner que les scenarios de cette application")
            @RequestParam(required = false) UUID applicationId) {
        return applicationId != null ? scenarioService.listByApplication(applicationId) : scenarioService.list();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Recuperer un scenario par id")
    @ApiResponse(responseCode = "404", description = "Scenario introuvable")
    public ScenarioResponse getById(@PathVariable UUID id) {
        return scenarioService.getById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Creer un scenario", description = "Reserve a SUPER_ADMIN et PERFORMANCE_ENGINEER. Actif (ACTIVE) des sa creation.")
    @ApiResponse(responseCode = "201", description = "Scenario cree")
    @ApiResponse(responseCode = "404", description = "Application referencee introuvable")
    public ScenarioResponse create(@Valid @RequestBody ScenarioRequest request,
                                    @AuthenticationPrincipal Jwt jwt,
                                    Authentication authentication) {
        CurrentUser currentUser = CurrentUser.from(jwt, authentication);
        return scenarioService.create(request, currentUser);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Modifier un scenario", description = "Reserve a SUPER_ADMIN et PERFORMANCE_ENGINEER.")
    @ApiResponse(responseCode = "404", description = "Scenario ou Application referencee introuvable")
    public ScenarioResponse update(@PathVariable UUID id, @Valid @RequestBody ScenarioRequest request) {
        return scenarioService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Supprimer un scenario", description = "Reserve a SUPER_ADMIN et PERFORMANCE_ENGINEER. Refuse (409) si des Steps y sont encore rattaches.")
    @ApiResponse(responseCode = "204", description = "Scenario supprime")
    @ApiResponse(responseCode = "409", description = "Des etapes sont encore rattachees a ce scenario")
    public void delete(@PathVariable UUID id) {
        scenarioService.delete(id);
    }
}
