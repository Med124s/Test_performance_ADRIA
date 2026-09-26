package com.loadpilot.backend.controller;

import com.loadpilot.backend.dto.request.ExecutionHistoryFilterRequest;
import com.loadpilot.backend.dto.request.ExecutionRequest;
import com.loadpilot.backend.dto.response.ExecutionDetailResponse;
import com.loadpilot.backend.dto.response.ExecutionHistoryResponse;
import com.loadpilot.backend.dto.response.ExecutionResponse;
import com.loadpilot.backend.dto.response.ExecutionStatusResponse;
import com.loadpilot.backend.dto.response.ExecutionReportResponse;
import com.loadpilot.backend.dto.response.PagedResponse;
import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.security.CurrentUser;
import com.loadpilot.backend.service.AppUserSyncService;
import com.loadpilot.backend.service.AuditLogService;
import com.loadpilot.backend.service.ExecutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/executions - lecture ouverte a tout utilisateur authentifie (voir
 * SecurityConfig : anyRequest().authenticated() couvre deja les GET) ;
 * lancement/annulation/relance reserves a SUPER_ADMIN et
 * PERFORMANCE_ENGINEER.
 *
 * P0-A : POST est desormais ASYNCHRONE - repond 202 (Accepted) avec
 * l'Execution QUEUED des sa creation, ne bloque plus jusqu'a la fin reelle
 * du test de charge (voir ExecutionServiceImpl). GET /{id}/status permet un
 * suivi leger (polling) sans recharger le detail complet a chaque appel.
 */
@RestController
@RequestMapping("/api/executions")
@RequiredArgsConstructor
@Tag(name = "Executions", description = "Executions HTTP reelles d'un Scenario (statuts RUNNING/SUCCESS/FAILED/CANCELLED)")
public class ExecutionController {

    private final ExecutionService executionService;
    private final AppUserSyncService appUserSyncService;
    private final AuditLogService auditLogService;

    /** ?scenarioId= filtre par scenario (meme convention que Scenarios/Steps). */
    @GetMapping
    @Operation(summary = "Lister les executions", description = "Filtrable par scenarioId, les plus recentes en premier.")
    @ApiResponse(responseCode = "404", description = "scenarioId fourni mais introuvable")
    public List<ExecutionResponse> list(
            @Parameter(description = "Filtre optionnel : ne retourner que les executions de ce scenario")
            @RequestParam(required = false) UUID scenarioId) {
        return scenarioId != null ? executionService.listByScenario(scenarioId) : executionService.list();
    }

    /**
     * P1-A — historique paginable/filtrable/triable (segment litteral
     * "/history", toujours prioritaire sur "/{id}" en specificite de
     * pattern Spring MVC - meme principe deja etabli par
     * AuditLogController pour "/stats"/"/export", voir sa Javadoc).
     *
     * "sort" : whitelist stricte (voir resolveHistorySort) - jamais un nom
     * de colonne fourni par le client transmis tel quel a la base.
     *
     * P1-C (prompt section 11) — audite reellement chaque consultation
     * (AuditAction.VIEW_HISTORY) : seule exception deliberee a la regle
     * "jamais auditer une simple lecture" suivie ailleurs dans ce backend -
     * voir AuditAction.VIEW_HISTORY pour la justification complete et le
     * rapport P1-C pour la discussion du volume que cela genere (une entree
     * par page consultee, y compris re-pagination).
     */
    @GetMapping("/history")
    @Operation(summary = "Historique paginable des executions", description = "Filtres combinables en ET : status, scenarioId, applicationId, dateFrom, dateTo, search (nom de scenario/application ou id d'execution). Tri whitelist : startedAt|duration|status.")
    public PagedResponse<ExecutionHistoryResponse> history(
            @Parameter(description = "Filtre par statut") @RequestParam(required = false) ExecutionStatus status,
            @Parameter(description = "Filtre par scenario") @RequestParam(required = false) UUID scenarioId,
            @Parameter(description = "Filtre par application") @RequestParam(required = false) UUID applicationId,
            @Parameter(description = "Borne de date de debut (incluse, sur startedAt)") @RequestParam(required = false) Instant dateFrom,
            @Parameter(description = "Borne de date de fin (incluse, sur startedAt)") @RequestParam(required = false) Instant dateTo,
            @Parameter(description = "Recherche textuelle : nom de scenario, nom d'application, ou id d'execution") @RequestParam(required = false) String search,
            @Parameter(description = "Numero de page (0-index)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Taille de page") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "colonne,direction - colonnes autorisees : startedAt|duration|status (defaut startedAt,desc)")
            @RequestParam(required = false) String sort) {
        ExecutionHistoryFilterRequest filter = new ExecutionHistoryFilterRequest(status, scenarioId, applicationId, dateFrom, dateTo, search);
        PagedResponse<ExecutionHistoryResponse> result = executionService.getHistory(filter, page, size, resolveHistorySort(sort));
        auditLogService.record(AuditAction.VIEW_HISTORY, AuditModule.EXECUTION, AuditResult.SUCCESS,
                "History viewed: page " + page + ", size " + size + ", " + result.totalElements() + " total matching");
        return result;
    }

    /**
     * Traduit "colonne,direction" en un Sort JPA sur une colonne
     * REELLEMENT autorisee - toute valeur absente/invalide/non listee
     * retombe silencieusement sur le tri par defaut (startedAt desc),
     * jamais une exception pour un simple parametre de tri malforme, et
     * jamais une colonne SQL arbitraire fournie par le client (prompt
     * P1-A, section 7 : "le tri doit etre securise").
     */
    private static Sort resolveHistorySort(String sort) {
        Map<String, String> allowedColumns = Map.of(
                "startedAt", "startedAt",
                "duration", "duration",
                "status", "status");
        Sort.Direction direction = Sort.Direction.DESC;
        String column = "startedAt";
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",", 2);
            String requestedColumn = parts[0].trim();
            if (allowedColumns.containsKey(requestedColumn)) {
                column = allowedColumns.get(requestedColumn);
                if (parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim())) {
                    direction = Sort.Direction.ASC;
                }
            }
        }
        return Sort.by(direction, column);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Detail d'une execution", description = "Inclut le detail de chaque etape reellement executee (ExecutionStepResult).")
    @ApiResponse(responseCode = "404", description = "Execution introuvable")
    public ExecutionDetailResponse getById(@PathVariable UUID id) {
        return executionService.getById(id);
    }

    @GetMapping("/{id}/status")
    @Operation(summary = "Statut allege d'une execution", description = "Pense pour du polling frequent pendant un test de charge en cours (voir ExecutionStatusResponse) - jamais le detail complet des resultats par etape.")
    @ApiResponse(responseCode = "404", description = "Execution introuvable")
    public ExecutionStatusResponse getStatus(@PathVariable UUID id) {
        return executionService.getStatus(id);
    }

    /**
     * P1-A — rapport statistique complet (percentiles reels calcules a la
     * demande, agregation par step, erreurs - voir
     * PerformanceStatisticsService). Meme autorisation que getById/getStatus
     * (lecture ouverte a tout role authentifie) : consulter le rapport
     * d'une execution n'est pas une action plus sensible que consulter son
     * detail - jamais une nouvelle matrice de permissions (prompt P1-A,
     * section 26).
     */
    @GetMapping("/{id}/report")
    @Operation(summary = "Rapport statistique d'une execution", description = "Statistiques globales, percentiles reels (p50/p75/p90/p95/p99/stdDev), agregation par step et liste des erreurs - calcules a la demande depuis ExecutionStepResult.")
    @ApiResponse(responseCode = "404", description = "Execution introuvable")
    public ExecutionReportResponse getReport(@PathVariable UUID id) {
        try {
            ExecutionReportResponse report = executionService.getReport(id);
            auditLogService.record(AuditAction.EXPORT_REPORT, AuditModule.EXECUTION, AuditResult.SUCCESS,
                    "Report viewed for execution " + id);
            return report;
        } catch (RuntimeException e) {
            auditLogService.record(AuditAction.EXPORT_REPORT, AuditModule.EXECUTION, AuditResult.FAILURE,
                    "Report view failed for execution " + id + ": " + e.getClass().getSimpleName());
            throw e;
        }
    }

    /**
     * P1-A — export CSV du meme rapport, memes autorisations exactement que
     * sa consultation (prompt section 26 : "un utilisateur ne doit pas
     * pouvoir télécharger le report d'une execution qu'il n'a pas le droit
     * de consulter"). Structure en sections (voir
     * ExecutionServiceImpl.toReportCsv) ; echappement CSV + protection
     * anti-injection de formule (voir CsvUtils) - jamais de header
     * Authorization/JWT/mot de passe dans le contenu (aucun de ces champs
     * n'existe dans les donnees sources, voir ReportErrorEntry).
     */
    @GetMapping("/{id}/report/export")
    @Operation(summary = "Exporter le rapport d'une execution en CSV", description = "Retourne un fichier CSV valide (RFC 4180), encodage UTF-8, protege contre l'injection de formule (CSV injection).")
    @ApiResponse(responseCode = "200", description = "Fichier CSV", content = @Content(mediaType = "text/csv"))
    @ApiResponse(responseCode = "404", description = "Execution introuvable")
    public ResponseEntity<byte[]> exportReport(@PathVariable UUID id) {
        try {
            byte[] csvBytes = executionService.exportReportCsv(id).getBytes(StandardCharsets.UTF_8);
            auditLogService.record(AuditAction.EXPORT_CSV, AuditModule.EXECUTION, AuditResult.SUCCESS,
                    "Report exported as CSV for execution " + id);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"execution-report-" + id + ".csv\"")
                    .body(csvBytes);
        } catch (RuntimeException e) {
            auditLogService.record(AuditAction.EXPORT_CSV, AuditModule.EXECUTION, AuditResult.FAILURE,
                    "CSV export failed for execution " + id + ": " + e.getClass().getSimpleName());
            throw e;
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Lancer l'execution d'un scenario", description = "ASYNCHRONE (P0-A) : cree reellement l'Execution (QUEUED) et planifie son execution reelle (avec charge concurrente si configuree sur le Scenario) sans bloquer la reponse HTTP. Suivre via GET /{id} ou /{id}/status. Reserve a SUPER_ADMIN et PERFORMANCE_ENGINEER.")
    @ApiResponse(responseCode = "202", description = "Execution acceptee et planifiee (statut QUEUED dans la reponse)")
    @ApiResponse(responseCode = "404", description = "Scenario introuvable")
    @ApiResponse(responseCode = "409", description = "Le scenario ne possede aucune etape a executer")
    public ExecutionResponse execute(@Valid @RequestBody ExecutionRequest request,
                                      @AuthenticationPrincipal Jwt jwt,
                                      Authentication authentication) {
        AppUser triggeredBy = appUserSyncService.sync(CurrentUser.from(jwt, authentication));
        return executionService.execute(request, triggeredBy.getId());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Annuler une execution", description = "Annulation REELLE (P0-A) : interrompt les requetes HTTP en vol pour une Execution RUNNING, ou empeche tout demarrage pour une Execution QUEUED. Reserve a SUPER_ADMIN et PERFORMANCE_ENGINEER.")
    @ApiResponse(responseCode = "404", description = "Execution introuvable")
    @ApiResponse(responseCode = "409", description = "L'execution est deja terminee, ou plus active en memoire (redemarrage serveur)")
    public ExecutionResponse cancel(@PathVariable UUID id) {
        return executionService.cancel(id);
    }

    @PostMapping("/{id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Relancer le scenario d'une execution existante", description = "Cree une NOUVELLE Execution QUEUED (asynchrone) pour le meme scenario ; l'execution d'origine n'est pas modifiee. Reserve a SUPER_ADMIN et PERFORMANCE_ENGINEER.")
    @ApiResponse(responseCode = "202", description = "Nouvelle execution acceptee et planifiee")
    @ApiResponse(responseCode = "404", description = "Execution d'origine introuvable")
    public ExecutionResponse retry(@PathVariable UUID id,
                                    @AuthenticationPrincipal Jwt jwt,
                                    Authentication authentication) {
        AppUser triggeredBy = appUserSyncService.sync(CurrentUser.from(jwt, authentication));
        return executionService.retry(id, triggeredBy.getId());
    }
}
