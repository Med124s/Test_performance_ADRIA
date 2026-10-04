package com.loadpilot.backend.controller;

import com.loadpilot.backend.service.impl.DeletionServiceImpl;
import com.loadpilot.backend.service.impl.ScenarioBulkService;
import com.loadpilot.backend.service.impl.ScenarioStatusService;
import com.loadpilot.backend.dto.request.ScenarioBulkRequest;
import com.loadpilot.backend.dto.response.BulkActionResponse;
import com.loadpilot.backend.exception.LoadConfigurationException;
import com.loadpilot.backend.dto.response.DeletionImpactResponse;
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
    private final DeletionServiceImpl deletionService;
    private final ScenarioStatusService statusService;
    private final ScenarioBulkService bulkService;

    /** ?applicationId= filtre par application (voir Phase 7) - meme
     * endpoint plutot que dupliquer un chemin dedie. */
    @GetMapping
    @Operation(summary = "Lister les scenarios",
            description = "Sans le parametre page : liste complete (filtrable par applicationId). Avec page : reponse paginee "
                    + "{content, page, size, totalElements, totalPages} ; size borne a 5..100 ; sort=createdAt|updatedAt|name,asc|desc ; "
                    + "search sur nom, application et description ; status = ACTIVE, INACTIVE ou DRAFT.")
    @ApiResponse(responseCode = "404", description = "applicationId fourni mais introuvable")
    public Object list(
            @Parameter(description = "Filtre optionnel : ne retourner que les scenarios de cette application")
            @RequestParam(required = false) UUID applicationId,
            @Parameter(description = "true : scenarios archives ou d'une application archivee (masques par defaut)")
            @RequestParam(defaultValue = "false") boolean archived,
            @RequestParam(required = false) Integer page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status) {
        if (page != null) {
            return scenarioService.page(page, size, sort, search, status, applicationId, archived);
        }
        return applicationId != null ? scenarioService.listByApplication(applicationId, archived) : scenarioService.list(archived);
    }

    @PutMapping("/{id}/full")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Modifier un scenario et ses etapes en une operation",
            description = "Transactionnel : informations, CSV, variables, charge, puis etapes creees (sans id), modifiees (avec id), "
                    + "supprimees (absentes) et reordonnees (ordre de la liste). Refuse (409) si une execution est en cours.")
    @ApiResponse(responseCode = "409", description = "Une execution du scenario est en cours, ou scenario archive")
    @ApiResponse(responseCode = "400", description = "Etape ou parametre invalide (rien n'est enregistre)")
    public com.loadpilot.backend.dto.response.ScenarioFullResponse updateFull(@PathVariable UUID id,
            @Valid @RequestBody com.loadpilot.backend.dto.request.ScenarioFullRequest request) {
        return scenarioService.updateFull(id, request);
    }

    // ------------------------------------------------------------ activation (statut INACTIVE)

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Activer un scenario",
            description = "Le scenario redevient executable. Ses planifications suspendues (pausedSchedules) ne sont pas "
                    + "reactivees d'office : voir POST /{id}/resume-schedules.")
    public ScenarioResponse activate(@PathVariable UUID id) {
        statusService.activate(id);
        return scenarioService.getById(id);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Desactiver un scenario",
            description = "Le scenario reste visible et modifiable mais n'est plus executable ; ses planifications sont suspendues. "
                    + "Refuse (409) si une execution est en cours, (400) pour un brouillon.")
    @ApiResponse(responseCode = "409", description = "Une execution est en cours")
    public ScenarioResponse deactivate(@PathVariable UUID id) {
        statusService.deactivate(id);
        return scenarioService.getById(id);
    }

    @PostMapping("/{id}/resume-schedules")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Reactiver les planifications suspendues par la desactivation",
            description = "Les planifications ponctuelles dont la date est passee restent desactivees (jamais de rattrapage).")
    public ScenarioStatusService.ResumeResult resumeSchedules(@PathVariable UUID id) {
        return statusService.resumeSchedules(id);
    }

    // ------------------------------------------------------------ actions groupees

    @PostMapping("/bulk-deletion-impact")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Impact cumule d'une action groupee (etapes, executions, planifications)")
    public DeletionImpactResponse bulkDeletionImpact(@Valid @RequestBody ScenarioBulkRequest request) {
        return deletionService.scenariosImpact(request.ids());
    }

    @PostMapping("/bulk-archive")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Archiver plusieurs scenarios", description = "Resultat par scenario (succes partiels possibles, ex. execution en cours).")
    public BulkActionResponse bulkArchive(@Valid @RequestBody ScenarioBulkRequest request) {
        return bulkService.archive(request.ids());
    }

    @PostMapping("/bulk-restore")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Restaurer plusieurs scenarios archives", description = "Resultat par scenario.")
    public BulkActionResponse bulkRestore(@Valid @RequestBody ScenarioBulkRequest request) {
        return bulkService.restore(request.ids());
    }

    @PostMapping("/bulk-delete")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Supprimer definitivement plusieurs scenarios",
            description = "Reserve a SUPER_ADMIN. Cascade complete par scenario ; resultat par scenario (refus si execution en cours).")
    public BulkActionResponse bulkDelete(@Valid @RequestBody ScenarioBulkRequest request) {
        return bulkService.deletePermanently(request.ids());
    }

    @PostMapping("/bulk-status")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Activer ou desactiver plusieurs scenarios", description = "status = ACTIVE ou INACTIVE. Resultat par scenario.")
    public BulkActionResponse bulkStatus(@Valid @RequestBody ScenarioBulkRequest request) {
        String status = request.status() == null ? "" : request.status().trim().toUpperCase();
        return switch (status) {
            case "ACTIVE" -> bulkService.activate(request.ids());
            case "INACTIVE" -> bulkService.deactivate(request.ids());
            default -> throw new LoadConfigurationException("Statut cible inconnu : " + request.status() + " (ACTIVE ou INACTIVE).");
        };
    }

    @PostMapping("/bulk-resume-schedules")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Reactiver les planifications suspendues de plusieurs scenarios", description = "Resultat par scenario.")
    public BulkActionResponse bulkResumeSchedules(@Valid @RequestBody ScenarioBulkRequest request) {
        return bulkService.resumeSchedules(request.ids());
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
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Supprimer definitivement un scenario",
            description = "Reserve a SUPER_ADMIN. Cascade complete (etapes, executions, resultats, planifications, metriques) dans une "
                    + "seule transaction. Refuse (409) si une execution est en cours. Alternative non destructive : POST /{id}/archive.")
    @ApiResponse(responseCode = "204", description = "Scenario supprime definitivement")
    @ApiResponse(responseCode = "409", description = "Une execution est en cours")
    public void delete(@PathVariable UUID id) {
        scenarioService.delete(id);
    }

    @GetMapping("/{id}/deletion-impact")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Impact d'une suppression ou d'un archivage de scenario")
    public DeletionImpactResponse deletionImpact(@PathVariable UUID id) {
        return deletionService.scenarioImpact(id);
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Archiver un scenario", description = "Masque des listes, historique conserve, restaurable. Refuse (409) si une execution est en cours.")
    public ScenarioResponse archive(@PathVariable UUID id) {
        deletionService.archiveScenario(id);
        return scenarioService.getById(id);
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Restaurer un scenario archive", description = "Refuse (409) si son application est archivee.")
    public ScenarioResponse restore(@PathVariable UUID id) {
        deletionService.restoreScenario(id);
        return scenarioService.getById(id);
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Terminer un brouillon",
            description = "DRAFT -> ACTIVE apres validation complete (au moins une etape, etapes valides, CSV valide, duree ou "
                    + "nombre de passages). Idempotent pour un scenario deja actif.")
    @ApiResponse(responseCode = "400", description = "Brouillon incomplet (message precis)")
    public ScenarioResponse publish(@PathVariable UUID id) {
        return scenarioService.publish(id);
    }

    @DeleteMapping("/{id}/draft")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Supprimer un brouillon",
            description = "Brouillons (DRAFT) uniquement - ex. « Quitter sans enregistrer » dans l'assistant. 409 pour un scenario actif.")
    @ApiResponse(responseCode = "204", description = "Brouillon supprime")
    @ApiResponse(responseCode = "409", description = "Le scenario n'est pas un brouillon")
    public void deleteDraft(@PathVariable UUID id) {
        scenarioService.deleteDraft(id);
    }
}
