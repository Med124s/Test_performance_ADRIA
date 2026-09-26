package com.loadpilot.backend.controller;

import com.loadpilot.backend.dto.request.AuditLogFilterRequest;
import com.loadpilot.backend.dto.response.AuditLogResponse;
import com.loadpilot.backend.dto.response.AuditStatsResponse;
import com.loadpilot.backend.dto.response.PagedResponse;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.service.AuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
 * /api/audit-logs - contient des informations sensibles d'administration
 * (voir Phase 12, section 13) : reserve a SUPER_ADMIN et
 * PERFORMANCE_ENGINEER, VIEWER explicitement exclu. @PreAuthorize pose au
 * niveau de la CLASSE : s'applique uniformement a list/detail/stats/export,
 * une seule regle a maintenir plutot que 4 annotations dupliquees.
 *
 * Lecture seule (aucun POST/PUT/PATCH/DELETE) : les AuditLog ne sont jamais
 * crees via cette API, uniquement par le backend lui-meme (voir
 * AuditLogService.record, appele depuis les services metier).
 *
 * "/stats" et "/export" sont des segments litteraux : Spring MVC les
 * fait toujours correspondre en priorite sur "/{id}" (specificite de
 * pattern, independante de l'ordre de declaration des methodes) - verifie
 * explicitement par AuditLogControllerTest.
 */
@RestController
@RequestMapping("/api/audit-logs")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
@Tag(name = "Audit", description = "Trace des actions importantes (qui/quoi/quand/ou/resultat) - reserve a SUPER_ADMIN et PERFORMANCE_ENGINEER, VIEWER exclu (403)")
public class AuditLogController {

    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "Lister/filtrer les entrees d'audit (paginee)", description = "Filtres combinables en ET : userId, username, action, module, result, from, to. Reserve a SUPER_ADMIN/PERFORMANCE_ENGINEER.")
    public PagedResponse<AuditLogResponse> list(
            @Parameter(description = "Filtre par identifiant Keycloak de l'auteur") @RequestParam(required = false) String userId,
            @Parameter(description = "Filtre par username") @RequestParam(required = false) String username,
            @Parameter(description = "Filtre par action (CREATE/READ/UPDATE/DELETE/TEST/EXECUTE/RETRY/CANCEL/LOGIN/LOGOUT)") @RequestParam(required = false) AuditAction action,
            @Parameter(description = "Filtre par module fonctionnel") @RequestParam(required = false) AuditModule module,
            @Parameter(description = "Filtre par resultat (SUCCESS/FAILURE)") @RequestParam(required = false) AuditResult result,
            @Parameter(description = "Borne de date de debut (incluse)") @RequestParam(required = false) Instant from,
            @Parameter(description = "Borne de date de fin (incluse)") @RequestParam(required = false) Instant to,
            @Parameter(description = "Numero de page (0-index)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Taille de page") @RequestParam(defaultValue = "20") int size) {
        AuditLogFilterRequest filter = new AuditLogFilterRequest(userId, username, action, module, result, from, to);
        return auditLogService.search(filter, page, size);
    }

    @GetMapping("/stats")
    @Operation(summary = "Statistiques d'audit", description = "totalActions/successfulActions/failedActions/actionsByModule/actionsByAction, calcules en temps reel sur toute la base.")
    public AuditStatsResponse stats() {
        return auditLogService.getStats();
    }

    @GetMapping("/export")
    @Operation(summary = "Exporter les entrees d'audit en CSV", description = "Respecte les memes filtres que la liste. Retourne un fichier CSV valide (RFC 4180), encodage UTF-8.")
    @ApiResponse(responseCode = "200", description = "Fichier CSV", content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "text/csv"))
    public ResponseEntity<byte[]> export(
            @Parameter(description = "Filtre par identifiant Keycloak de l'auteur") @RequestParam(required = false) String userId,
            @Parameter(description = "Filtre par username") @RequestParam(required = false) String username,
            @Parameter(description = "Filtre par action") @RequestParam(required = false) AuditAction action,
            @Parameter(description = "Filtre par module") @RequestParam(required = false) AuditModule module,
            @Parameter(description = "Filtre par resultat") @RequestParam(required = false) AuditResult result,
            @Parameter(description = "Borne de date de debut (incluse)") @RequestParam(required = false) Instant from,
            @Parameter(description = "Borne de date de fin (incluse)") @RequestParam(required = false) Instant to) {
        AuditLogFilterRequest filter = new AuditLogFilterRequest(userId, username, action, module, result, from, to);
        byte[] csvBytes = auditLogService.exportCsv(filter).getBytes(StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-logs.csv\"")
                .body(csvBytes);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Recuperer une entree d'audit par id")
    @ApiResponse(responseCode = "404", description = "Entree d'audit introuvable")
    public AuditLogResponse getById(@PathVariable UUID id) {
        return auditLogService.getById(id);
    }
}
