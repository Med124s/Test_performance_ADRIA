package com.loadpilot.backend.controller;

import com.loadpilot.backend.dto.request.AuditLogFilterRequest;
import com.loadpilot.backend.dto.response.AuditLogResponse;
import com.loadpilot.backend.dto.response.AuditStatsResponse;
import com.loadpilot.backend.dto.response.PagedResponse;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.service.AuditLogService;
import com.loadpilot.backend.service.audit.AuditCategory;
import com.loadpilot.backend.service.audit.AuditEvent;
import com.loadpilot.backend.service.audit.AuditTargetType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/audit-logs - journal d'audit, RESERVE AU SUPER_ADMIN (@PreAuthorize au
 * niveau de la classe : liste, detail, utilisateurs, statistiques et export).
 *
 * Lecture seule (aucun POST/PUT/PATCH/DELETE) : les entrees ne sont jamais
 * creees, modifiees ni supprimees via cette API - uniquement ecrites par le
 * backend lui-meme (AuditLogService.record) et purgees automatiquement apres
 * la duree de conservation (AuditRetentionJob).
 *
 * "/stats", "/actors" et "/export" sont des segments litteraux : Spring MVC
 * les fait toujours correspondre en priorite sur "/{id}".
 */
@RestController
@RequestMapping("/api/audit-logs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Tag(name = "Audit", description = "Journal d'audit (qui / quoi / quand / ou / resultat) - reserve a SUPER_ADMIN")
public class AuditLogController {

    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "Lister / filtrer le journal (pagine, plus recent d'abord)",
            description = "Filtres combinables en ET, tous appliques cote serveur : q (recherche libre), actor (username ou __system), "
                    + "category, result, from, to. userId / username / action / module restent acceptes.")
    public PagedResponse<AuditLogResponse> list(
            @Parameter(description = "Recherche libre : utilisateur, objet, description") @RequestParam(required = false) String q,
            @Parameter(description = "Utilisateur (username), ou __system pour les actions automatiques") @RequestParam(required = false) String actor,
            @Parameter(description = "Categorie (APPLICATIONS, SCENARIOS, EXECUTIONS, SCHEDULING, USERS, SECURITY, SETTINGS)") @RequestParam(required = false) AuditCategory category,
            @Parameter(description = "Filtre par identifiant (sub) de l'auteur") @RequestParam(required = false) String userId,
            @Parameter(description = "Filtre par username") @RequestParam(required = false) String username,
            @Parameter(description = "Filtre par action") @RequestParam(required = false) AuditAction action,
            @Parameter(description = "Filtre par module fonctionnel") @RequestParam(required = false) AuditModule module,
            @Parameter(description = "Filtre par resultat (SUCCESS/FAILURE)") @RequestParam(required = false) AuditResult result,
            @Parameter(description = "Borne de date de debut (incluse)") @RequestParam(required = false) Instant from,
            @Parameter(description = "Borne de date de fin (incluse)") @RequestParam(required = false) Instant to,
            @Parameter(description = "Numero de page (0-index)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Taille de page (200 au plus)") @RequestParam(defaultValue = "20") int size) {
        AuditLogFilterRequest filter = new AuditLogFilterRequest(q, actor, category, userId, username, action, module, result, from, to);
        return auditLogService.search(filter, page, size);
    }

    @GetMapping("/actors")
    @Operation(summary = "Utilisateurs presents dans le journal", description = "Pour la liste du filtre « Utilisateur ».")
    public List<String> actors() {
        return auditLogService.actors();
    }

    @GetMapping("/stats")
    @Operation(summary = "Statistiques d'audit", description = "totalActions/successfulActions/failedActions/actionsByModule/actionsByAction.")
    public AuditStatsResponse stats() {
        return auditLogService.getStats();
    }

    @GetMapping("/export")
    @Operation(summary = "Exporter le journal en CSV (francais)",
            description = "Memes filtres que la liste. Separateur « ; », UTF-8 avec BOM (ouverture directe dans Excel), aucun secret.")
    @ApiResponse(responseCode = "200", description = "Fichier CSV", content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "text/csv"))
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) AuditCategory category,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) AuditModule module,
            @RequestParam(required = false) AuditResult result,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        AuditLogFilterRequest filter = new AuditLogFilterRequest(q, actor, category, userId, username, action, module, result, from, to);
        byte[] csvBytes = auditLogService.exportCsv(filter).getBytes(StandardCharsets.UTF_8);
        // Un export est une vraie sortie de donnees : toujours journalise.
        auditLogService.record(AuditEvent.of(AuditAction.EXPORT_CSV, AuditModule.ADMIN)
                .target(AuditTargetType.SETTINGS, "audit-log", "Journal d’audit")
                .description("Export du journal d’audit au format CSV"));

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"journal-audit.csv\"")
                .body(csvBytes);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Recuperer une entree du journal par id")
    @ApiResponse(responseCode = "404", description = "Entree introuvable")
    public AuditLogResponse getById(@PathVariable UUID id) {
        return auditLogService.getById(id);
    }
}
