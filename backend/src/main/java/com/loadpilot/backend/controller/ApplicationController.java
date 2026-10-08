package com.loadpilot.backend.controller;

import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.access.AccessDeniedException;
import com.loadpilot.backend.service.impl.DeletionServiceImpl;
import com.loadpilot.backend.service.impl.ApplicationBulkService;
import com.loadpilot.backend.exception.LoadConfigurationException;
import com.loadpilot.backend.dto.response.DeletionImpactResponse;
import com.loadpilot.backend.dto.response.BulkActionResponse;
import com.loadpilot.backend.dto.request.BulkActionRequest;
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
    private final DeletionServiceImpl deletionService;
    private final ApplicationBulkService bulkService;

    /**
     * Sans "page" : liste complete (format historique, utilise par les autres
     * ecrans). Avec "page" : reponse paginee {content, page, size,
     * totalElements, totalPages}, filtree et triee cote serveur.
     */
    @GetMapping
    @Operation(summary = "Lister les applications",
            description = "Sans le parametre page : liste complete des applications non archivees (ou archivees si archived=true). "
                    + "Avec page : reponse paginee. size borne a 5..100 ; sort=champ,asc|desc parmi name, url, status, createdAt, updatedAt ; "
                    + "search sur nom/URL/description ; status = CONNECTED, FAILED, ERROR ou UNTESTED.")
    public Object list(@RequestParam(required = false) Integer page,
                       @RequestParam(defaultValue = "10") int size,
                       @RequestParam(required = false) String sort,
                       @RequestParam(required = false) String search,
                       @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "false") boolean archived) {
        if (page == null) {
            return applicationService.list(archived);
        }
        return applicationService.page(page, size, sort, search, status, archived);
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
    @Operation(summary = "Supprimer definitivement une application",
            description = "Reserve a SUPER_ADMIN. Cascade complete (scenarios, etapes, executions, resultats, planifications, metriques) "
                    + "dans une seule transaction. Refuse (409) si une execution est en cours. Alternative non destructive : POST /{id}/archive.")
    @ApiResponse(responseCode = "204", description = "Application supprimee definitivement")
    @ApiResponse(responseCode = "409", description = "Une execution est en cours")
    public void delete(@PathVariable UUID id) {
        applicationService.delete(id);
    }

    @GetMapping("/{id}/deletion-impact")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Impact d'une suppression ou d'un archivage",
            description = "Nombre de scenarios, etapes, executions et planifications concernes, et presence d'une execution en cours.")
    public DeletionImpactResponse deletionImpact(@PathVariable UUID id) {
        return deletionService.applicationsImpact(List.of(id));
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Archiver une application",
            description = "Suppression logique : masquee des listes, historique conserve, restaurable. Refuse (409) si une execution est en cours.")
    public ApplicationResponse archive(@PathVariable UUID id) {
        deletionService.archiveApplication(id);
        return applicationService.getById(id);
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Restaurer une application archivee")
    public ApplicationResponse restore(@PathVariable UUID id) {
        deletionService.restoreApplication(id);
        return applicationService.getById(id);
    }

    @PostMapping("/bulk-deletion-impact")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Impact cumule d'une action groupee")
    public DeletionImpactResponse bulkDeletionImpact(@Valid @RequestBody BulkActionRequest request) {
        return deletionService.applicationsImpact(request.ids());
    }

    @PostMapping("/bulk-archive")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Archiver ou restaurer plusieurs applications",
            description = "mode = ARCHIVE (defaut) ou RESTORE. Resultat detaille par application (succes partiels possibles).")
    public BulkActionResponse bulkArchive(@Valid @RequestBody BulkActionRequest request) {
        String mode = request.mode() == null ? "ARCHIVE" : request.mode().toUpperCase();
        return switch (mode) {
            case "ARCHIVE" -> bulkService.archive(request.ids());
            case "RESTORE" -> bulkService.restore(request.ids());
            default -> throw new LoadConfigurationException("Mode inconnu : " + request.mode() + " (ARCHIVE ou RESTORE).");
        };
    }

    @PostMapping("/bulk-delete")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Supprimer plusieurs applications",
            description = "mode = ARCHIVE (defaut) ou PERMANENT (SUPER_ADMIN uniquement). Resultat detaille par application.")
    public BulkActionResponse bulkDelete(@Valid @RequestBody BulkActionRequest request, Authentication authentication) {
        String mode = request.mode() == null ? "ARCHIVE" : request.mode().toUpperCase();
        return switch (mode) {
            case "ARCHIVE" -> bulkService.archive(request.ids());
            case "PERMANENT" -> {
                boolean superAdmin = authentication.getAuthorities().stream()
                        .anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
                if (!superAdmin) {
                    throw new AccessDeniedException("La suppression definitive est reservee au role SUPER_ADMIN.");
                }
                yield bulkService.deletePermanently(request.ids());
            }
            default -> throw new LoadConfigurationException("Mode inconnu : " + request.mode() + " (ARCHIVE ou PERMANENT).");
        };
    }

    @PostMapping("/{id}/test")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Tester la disponibilite reelle de l'application", description = "Envoie une vraie requete HTTP vers l'URL de l'application et met a jour son statut (CONNECTED/FAILED/ERROR).")
    public ApplicationTestResponse testAvailability(@PathVariable UUID id) {
        return applicationService.testAvailability(id);
    }

    @PostMapping("/monitoring/test")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Tester le monitoring CPU / RAM", description = "Lit deux fois (a une seconde d'intervalle) le CPU et la RAM du processus de l'application sur l'endpoint Prometheus ou Actuator saisi. Rien n'est enregistre.")
    public com.loadpilot.backend.dto.response.MonitoringTestResponse testMonitoring(
            @Valid @RequestBody com.loadpilot.backend.dto.request.MonitoringTestRequest request) {
        return applicationService.testMonitoring(request);
    }
}
