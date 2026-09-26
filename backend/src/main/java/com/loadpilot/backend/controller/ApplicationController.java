package com.loadpilot.backend.controller;

import com.loadpilot.backend.dto.request.ApplicationRequest;
import com.loadpilot.backend.dto.response.ApplicationResponse;
import com.loadpilot.backend.dto.response.ApplicationTestResponse;
import com.loadpilot.backend.security.CurrentUser;
import com.loadpilot.backend.service.ApplicationService;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/applications - lecture ouverte a tout utilisateur authentifie (voir
 * SecurityConfig : anyRequest().authenticated() couvre deja les GET, aucune
 * annotation supplementaire necessaire ici pour eviter de dupliquer la
 * regle) ; ecriture restreinte via @PreAuthorize selon la matrice Phase 6 :
 * SUPER_ADMIN et PERFORMANCE_ENGINEER peuvent creer/modifier/tester,
 * seul SUPER_ADMIN peut supprimer.
 */
@RestController
@RequestMapping("/api/applications")
@RequiredArgsConstructor
@Tag(name = "Applications", description = "Applications cibles testees par LoadPilot (URL, statut de disponibilite reel)")
public class ApplicationController {

    private final ApplicationService applicationService;

    @GetMapping
    @Operation(summary = "Lister les applications", description = "Retourne toutes les applications, les plus recentes en premier.")
    public List<ApplicationResponse> list() {
        return applicationService.list();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Recuperer une application par id")
    @ApiResponse(responseCode = "404", description = "Application introuvable")
    public ApplicationResponse getById(@PathVariable UUID id) {
        return applicationService.getById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Creer une application", description = "Reserve a SUPER_ADMIN et PERFORMANCE_ENGINEER. Aucun statut de disponibilite tant qu'aucun test n'a ete lance.")
    @ApiResponse(responseCode = "201", description = "Application creee")
    @ApiResponse(responseCode = "400", description = "Requete invalide (nom/URL manquants ou invalides)")
    public ApplicationResponse create(@Valid @RequestBody ApplicationRequest request,
                                       @AuthenticationPrincipal Jwt jwt,
                                       Authentication authentication) {
        CurrentUser currentUser = CurrentUser.from(jwt, authentication);
        return applicationService.create(request, currentUser);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Modifier une application", description = "Reserve a SUPER_ADMIN et PERFORMANCE_ENGINEER.")
    @ApiResponse(responseCode = "404", description = "Application introuvable")
    public ApplicationResponse update(@PathVariable UUID id, @Valid @RequestBody ApplicationRequest request) {
        return applicationService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Supprimer une application", description = "Reserve a SUPER_ADMIN. Refuse (409) si des Scenarios y sont encore rattaches.")
    @ApiResponse(responseCode = "204", description = "Application supprimee")
    @ApiResponse(responseCode = "409", description = "Des scenarios sont encore rattaches a cette application")
    public void delete(@PathVariable UUID id) {
        applicationService.delete(id);
    }

    @PostMapping("/{id}/test")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Tester la disponibilite reelle de l'application", description = "Envoie une vraie requete HTTP vers l'URL de l'application et met a jour son statut (CONNECTED/FAILED/ERROR).")
    public ApplicationTestResponse testAvailability(@PathVariable UUID id) {
        return applicationService.testAvailability(id);
    }
}
