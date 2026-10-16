package com.loadpilot.backend.controller;

import com.loadpilot.backend.dto.request.ExecutionBulkRequest;
import com.loadpilot.backend.dto.request.ExecutionHistoryFilterRequest;
import com.loadpilot.backend.dto.response.BulkActionResponse;
import com.loadpilot.backend.dto.response.ExecutionDeletionImpactResponse;
import com.loadpilot.backend.dto.response.ExecutionPageResponse;
import com.loadpilot.backend.dto.response.ExecutionRequestDetailResponse;
import com.loadpilot.backend.dto.response.ExecutionRequestRowResponse;
import com.loadpilot.backend.service.impl.ExecutionDeletionService;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import com.loadpilot.backend.service.audit.AuditEvent;
import com.loadpilot.backend.service.audit.AuditTargets;
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
import org.springframework.web.bind.annotation.PatchMapping;
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
    private final AuditTargets auditTargets;
    private final AuditLogService auditLogService;
    private final ExecutionDeletionService deletionService;
    private final com.loadpilot.backend.service.impl.ExecutionRequestDetailService requestDetailService;
    private final com.loadpilot.backend.service.impl.ExecutionTimelineService timelineService;
    private final com.loadpilot.backend.service.impl.ExecutionExtremesService extremesService;
    private final com.loadpilot.backend.service.impl.ExecutionComparisonService comparisonService;
    private final com.loadpilot.backend.service.impl.ExecutionKeepService keepService;
    private final com.loadpilot.backend.service.report.pdf.ReportPdfService reportPdfService;
    private final com.loadpilot.backend.service.report.export.ReportExportService reportExportService;

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
        // Consultation : journalisee seulement si « Journaliser aussi les consultations » est actif.
        auditLogService.record(AuditEvent.of(AuditAction.VIEW_HISTORY, AuditModule.EXECUTION).consultation()
                .description("Historique des exécutions consulté (page " + (page + 1) + ", " + result.totalElements() + " résultat(s))"));
        return result;
    }

    /**
     * Liste de la page Executions : memes filtres que /history (combinables en ET), pagination
     * serveur et compteurs par statut suivant ces filtres. Contrairement a /history, jamais
     * auditee (la page se rafraichit automatiquement pendant une execution).
     */
    @GetMapping("/page")
    @Operation(summary = "Page de la liste des executions (filtres + compteurs par statut)",
            description = "Filtres : status, scenarioId, applicationId, dateFrom, dateTo (bornes incluses, sur startedAt), search. "
                    + "size borne a 5..100. Tri whitelist : startedAt|duration|status (colonne,direction).")
    public ExecutionPageResponse page(
            @RequestParam(required = false) ExecutionStatus status,
            @RequestParam(required = false) UUID scenarioId,
            @RequestParam(required = false) UUID applicationId,
            @Parameter(description = "Borne de date de debut (incluse, sur startedAt)") @RequestParam(required = false) Instant dateFrom,
            @Parameter(description = "Borne de date de fin (incluse, sur startedAt)") @RequestParam(required = false) Instant dateTo,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String sort,
            @Parameter(description = "true : seulement les executions terminees (page Rapports)") @RequestParam(defaultValue = "false") boolean finished) {
        ExecutionHistoryFilterRequest filter = new ExecutionHistoryFilterRequest(status, scenarioId, applicationId, dateFrom, dateTo, search, finished);
        return executionService.getPage(filter, Math.max(0, page), Math.min(100, Math.max(5, size)), resolveHistorySort(sort));
    }

    @PostMapping("/deletion-impact")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Impact d'une suppression d'executions",
            description = "Nombre d'executions, de resultats de requetes et de metriques supprimes ; executions actives (ignorees).")
    public ExecutionDeletionImpactResponse deletionImpact(@Valid @RequestBody ExecutionBulkRequest request) {
        return deletionService.impact(request.ids());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Supprimer definitivement une execution",
            description = "Supprime l'execution, ses resultats de requetes et ses metriques. Refuse (409) pour une execution en attente, "
                    + "en cours ou en pause. Reserve a SUPER_ADMIN et PERFORMANCE_ENGINEER (comme la suppression d'une etape).")
    @ApiResponse(responseCode = "409", description = "Execution en attente, en cours ou en pause")
    public void delete(@PathVariable UUID id) {
        deletionService.delete(id);
    }

    @PatchMapping("/{id}/keep")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Conserver une execution (jamais purgee)",
            description = "keep = true : resultats et detail des requetes jamais supprimes par la purge automatique ; false : suivent "
                    + "la duree de conservation. Refuse (409) si le detail a deja ete supprime. Memes roles que la suppression.")
    @ApiResponse(responseCode = "409", description = "Detail des requetes deja supprime")
    public com.loadpilot.backend.dto.response.ResultsRetentionResponse keep(@PathVariable UUID id,
            @Valid @RequestBody com.loadpilot.backend.dto.request.ExecutionKeepRequest request) {
        return keepService.setKeep(id, request.keep());
    }

    @PostMapping("/bulk-delete")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Supprimer definitivement plusieurs executions",
            description = "Chaque execution dans sa propre transaction : les executions actives sont refusees, les autres supprimees. "
                    + "Resultat detaille par execution.")
    public BulkActionResponse bulkDelete(@Valid @RequestBody ExecutionBulkRequest request) {
        return deletionService.deleteMany(request.ids());
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
    public ExecutionDetailResponse getById(@PathVariable UUID id,
            @Parameter(description = "false : sans la liste des requetes (voir /{id}/results, paginee)")
            @RequestParam(defaultValue = "true") boolean includeResults) {
        return executionService.getById(id, includeResults);
    }

    @GetMapping("/{id}/results")
    @Operation(summary = "Requetes d'une execution (paginees)",
            description = "Filtres : stepId (ou stepName pour une etape supprimee depuis), virtualUser, iteration, failedOnly. "
                    + "detailKind indique si le detail complet a ete conserve (FAILURE / SAMPLE). size borne a 5..100.")
    @ApiResponse(responseCode = "404", description = "Execution introuvable")
    public PagedResponse<ExecutionRequestRowResponse> results(
            @PathVariable UUID id,
            @RequestParam(required = false) UUID stepId,
            @RequestParam(required = false) String stepName,
            @RequestParam(required = false) Integer virtualUser,
            @RequestParam(required = false) Integer iteration,
            @RequestParam(defaultValue = "false") boolean failedOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "TIME (chronologique, defaut), SLOWEST (plus lentes d'abord) ou FASTEST (plus rapides d'abord)")
            @RequestParam(defaultValue = "TIME") com.loadpilot.backend.service.impl.ExecutionRequestDetailService.RowSort sort) {
        return requestDetailService.list(id, stepId, stepName, virtualUser, iteration, failedOnly,
                Math.max(0, page), Math.min(100, Math.max(5, size)), sort);
    }

    @GetMapping("/compare")
    @Operation(summary = "Comparer 2 a 4 executions terminees",
            description = "Une colonne par execution (ordre chronologique) : scenario, date, charge, requetes, debit, P95, p99, taux "
                    + "d'erreurs, CPU / RAM max de l'application (si monitoring), et P95 de chaque etape - memes calculs que les rapports. "
                    + "warning si les executions n'utilisent pas le meme scenario.")
    @ApiResponse(responseCode = "400", description = "Moins de 2 ou plus de 4 executions, ou execution non terminee")
    @ApiResponse(responseCode = "404", description = "Execution introuvable")
    public com.loadpilot.backend.dto.response.ExecutionComparisonResponse compare(@RequestParam List<UUID> ids) {
        return comparisonService.compare(ids);
    }

    @GetMapping("/{id}/extremes")
    @Operation(summary = "Requetes les plus rapides ou les plus lentes (cartes Minimum / Maximum du rapport)",
            description = "kind = MIN ou MAX : les 10 requetes extremes, la premiere etant exactement la valeur de la carte, avec "
                    + "etape, utilisateur virtuel, iteration, heure, seconde dans le test, code HTTP, et au meme moment utilisateurs "
                    + "actifs, debit et P95 de l'intervalle. Calcule par la base (index), sans charger toutes les requetes.")
    @ApiResponse(responseCode = "404", description = "Execution introuvable")
    public com.loadpilot.backend.dto.response.ExecutionExtremesResponse extremes(@PathVariable UUID id,
            @RequestParam(defaultValue = "MAX") String kind) {
        if (!"MIN".equalsIgnoreCase(kind) && !"MAX".equalsIgnoreCase(kind)) {
            throw new com.loadpilot.backend.exception.BadRequestException("kind doit valoir MIN ou MAX.");
        }
        return extremesService.extremes(id, "MAX".equalsIgnoreCase(kind));
    }

    @GetMapping("/{id}/timeline")
    @Operation(summary = "Analyse dans le temps d'une execution",
            description = "Utilisateurs virtuels actifs, debit, temps de reponse moyen et P95, par etape, erreurs par type, "
                    + "pauses, et Points cles - calcules cote serveur (pas de temps adapte a la duree, 300 points au plus). "
                    + "Mis en cache pour une execution terminee.")
    @ApiResponse(responseCode = "404", description = "Execution introuvable")
    public com.loadpilot.backend.dto.response.ExecutionTimelineResponse timeline(@PathVariable UUID id) {
        return timelineService.timeline(id);
    }

    @GetMapping("/{id}/results/{resultId}/detail")
    @Operation(summary = "Detail complet d'une requete (requete envoyee, reponse recue, verifications)",
            description = "Secrets masques, corps limites a 10 Ko. 404 si le detail n'a pas ete conserve pour cette requete.")
    @ApiResponse(responseCode = "404", description = "Requete introuvable, ou detail non conserve")
    public ExecutionRequestDetailResponse resultDetail(@PathVariable UUID id, @PathVariable UUID resultId) {
        return requestDetailService.detail(id, resultId);
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
    @Operation(summary = "Rapport statistique d'une execution", description = "Statistiques globales, percentiles reels (p50/p75/p90/p95/p99), debit hors temps de pause, agregation par step et liste des erreurs - calcules a la demande depuis ExecutionStepResult.")
    @ApiResponse(responseCode = "404", description = "Execution introuvable")
    public ExecutionReportResponse getReport(@PathVariable UUID id) {
        try {
            ExecutionReportResponse report = executionService.getReport(id);
            // Consultation (et non un export) : journalisee seulement si le reglage est actif.
            auditLogService.record(auditTargets.onExecution(AuditEvent.of(AuditAction.VIEW_REPORT, AuditModule.EXECUTION), id)
                    .consultation().description("Rapport consulté"));
            return report;
        } catch (RuntimeException e) {
            auditLogService.record(auditTargets.onExecution(AuditEvent.of(AuditAction.VIEW_REPORT, AuditModule.EXECUTION), id)
                    .consultation().description("Rapport inaccessible").failed(e));
            throw e;
        }
    }

    /**
     * Exports du rapport (memes droits que sa consultation : tout role authentifie), aussi pour un
     * rapport fige, 409 si l'execution n'est pas terminee, chaque export journalise. Aucun secret :
     * voir ReportTables.
     *  - CSV : UN tableau par fichier (?table=steps par defaut, errors, timeline, summary), au format
     *    « Excel en francais » (UTF-8 avec BOM, « ; », virgule decimale, dates jj/mm/aaaa hh:mm:ss) ;
     *  - CSV .zip : les quatre tableaux, chacun dans son fichier ;
     *  - Excel (.xlsx) : une feuille par tableau.
     */
    @GetMapping("/{id}/report/export")
    @Operation(summary = "Exporter un tableau du rapport en CSV",
            description = "table = steps (defaut), errors, timeline ou summary. UTF-8 avec BOM, separateur « ; », virgule decimale, "
                    + "dates jj/mm/aaaa hh:mm:ss, en-tetes en francais avec unites, protege contre l'injection de formule.")
    @ApiResponse(responseCode = "200", description = "Fichier CSV", content = @Content(mediaType = "text/csv"))
    @ApiResponse(responseCode = "404", description = "Execution introuvable")
    @ApiResponse(responseCode = "409", description = "Execution en attente, en cours ou en pause")
    public ResponseEntity<byte[]> exportReport(@PathVariable UUID id, @RequestParam(required = false) String table) {
        return export(id, AuditAction.EXPORT_CSV, "CSV", () -> reportExportService.csv(id, table));
    }

    @GetMapping("/{id}/report/export.zip")
    @Operation(summary = "Exporter les quatre tableaux du rapport en CSV (archive .zip)",
            description = "Resume, Etapes, Erreurs, Analyse dans le temps : un fichier CSV par tableau, au meme format que /report/export.")
    @ApiResponse(responseCode = "200", description = "Archive ZIP", content = @Content(mediaType = "application/zip"))
    public ResponseEntity<byte[]> exportReportZip(@PathVariable UUID id) {
        return export(id, AuditAction.EXPORT_CSV, "CSV", () -> reportExportService.csvZip(id));
    }

    @GetMapping("/{id}/report/xlsx")
    @Operation(summary = "Exporter le rapport en Excel (.xlsx)",
            description = "Feuilles Résumé, Étapes, Erreurs, Analyse dans le temps : en-tetes en gras et figes, filtres automatiques, "
                    + "nombres stockes comme nombres, dates jj/mm/aaaa hh:mm:ss.")
    @ApiResponse(responseCode = "200", description = "Classeur Excel",
            content = @Content(mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
    public ResponseEntity<byte[]> exportReportXlsx(@PathVariable UUID id) {
        return export(id, AuditAction.EXPORT_REPORT, "Excel", () -> reportExportService.xlsx(id));
    }

    private ResponseEntity<byte[]> export(UUID id, AuditAction action, String format,
                                          java.util.function.Supplier<com.loadpilot.backend.service.report.export.ReportExportService.ExportFile> producer) {
        try {
            com.loadpilot.backend.service.report.export.ReportExportService.ExportFile file = producer.get();
            auditLogService.record(auditTargets.onExecution(AuditEvent.of(action, AuditModule.EXECUTION), id)
                    .description("Rapport exporté au format " + file.label()));
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, file.contentType())
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.fileName() + "\"")
                    .body(file.content());
        } catch (RuntimeException e) {
            auditLogService.record(auditTargets.onExecution(AuditEvent.of(action, AuditModule.EXECUTION), id)
                    .description("Export " + format + " du rapport impossible").failed(e));
            throw e;
        }
    }

    /**
     * Rapport PDF (page Rapports) : memes droits que la consultation du rapport (tout role
     * authentifie). Genere cote serveur, fonctionne aussi pour un rapport fige. Journalise.
     */
    @GetMapping("/{id}/report/pdf")
    @Operation(summary = "Exporter le rapport d'une execution terminee en PDF",
            description = "Document en francais : en-tete, resultat et seuils, temps de reponse, analyse dans le temps, evolution des "
                    + "etapes, erreurs, CPU / RAM, points cles et conclusion. 409 si l'execution n'est pas terminee. Aucun secret.")
    @ApiResponse(responseCode = "200", description = "Fichier PDF", content = @Content(mediaType = "application/pdf"))
    @ApiResponse(responseCode = "404", description = "Execution introuvable")
    @ApiResponse(responseCode = "409", description = "Execution en attente, en cours ou en pause")
    public ResponseEntity<byte[]> exportReportPdf(@PathVariable UUID id) {
        try {
            com.loadpilot.backend.service.report.pdf.ReportPdfService.PdfFile file = reportPdfService.export(id);
            auditLogService.record(auditTargets.onExecution(AuditEvent.of(AuditAction.EXPORT_REPORT, AuditModule.EXECUTION), id)
                    .description("Rapport exporté au format PDF"));
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.fileName() + "\"")
                    .body(file.content());
        } catch (RuntimeException e) {
            auditLogService.record(auditTargets.onExecution(AuditEvent.of(AuditAction.EXPORT_REPORT, AuditModule.EXECUTION), id)
                    .description("Export PDF du rapport impossible").failed(e));
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

    @PostMapping("/{id}/pause")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Mettre une execution en pause",
            description = "Les utilisateurs virtuels n'envoient plus de nouvelle requete (les requetes en vol recoivent leur "
                    + "reponse). Le temps de pause n'est compte ni dans la duree du test ni dans le debit. Annulation "
                    + "automatique au-dela de app.execution.max-pause-minutes. Reserve a SUPER_ADMIN et PERFORMANCE_ENGINEER.")
    @ApiResponse(responseCode = "409", description = "L'execution n'est pas en cours (en attente, deja en pause ou terminee)")
    public ExecutionResponse pause(@PathVariable UUID id) {
        return executionService.pause(id);
    }

    @PostMapping("/{id}/resume")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Reprendre une execution en pause",
            description = "Le test reprend exactement la ou il s'etait arrete. Reserve a SUPER_ADMIN et PERFORMANCE_ENGINEER.")
    @ApiResponse(responseCode = "409", description = "L'execution n'est pas en pause")
    public ExecutionResponse resume(@PathVariable UUID id) {
        return executionService.resume(id);
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
