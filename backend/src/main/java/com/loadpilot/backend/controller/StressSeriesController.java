package com.loadpilot.backend.controller;

import com.loadpilot.backend.dto.request.StressSeriesRequest;
import com.loadpilot.backend.dto.response.StressSeriesResponse;
import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.security.CurrentUser;
import com.loadpilot.backend.service.AppUserSyncService;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.service.AuditLogService;
import com.loadpilot.backend.service.audit.AuditEvent;
import com.loadpilot.backend.service.audit.AuditTargetType;
import com.loadpilot.backend.service.impl.StressSeriesService;
import com.loadpilot.backend.service.report.pdf.ReportPdfService;
import io.swagger.v3.oas.annotations.media.Content;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stress-series")
@RequiredArgsConstructor
@Tag(name = "Series d'essais de stress", description = "Meme test de stress enchaine 3 ou 5 fois, pause entre deux essais, point de rupture consolide")
public class StressSeriesController {

    private final StressSeriesService stressSeriesService;
    private final AppUserSyncService appUserSyncService;
    private final AuditLogService auditLogService;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Lancer une serie d'essais de stress",
            description = "Scenario en mode stress, 3 ou 5 essais. Lance le premier essai (asynchrone) ; les suivants partent apres une pause de 30 s, "
                    + "le serveur restant reserve a la serie. Un essai non concluant arrete la serie.")
    @ApiResponse(responseCode = "400", description = "Scenario hors mode stress, ou nombre d'essais different de 3 et 5")
    @ApiResponse(responseCode = "429", description = "Une autre execution est en cours : un test de stress tourne seul")
    public StressSeriesResponse start(@Valid @RequestBody StressSeriesRequest request, @AuthenticationPrincipal Jwt jwt,
            Authentication authentication) {
        AppUser triggeredBy = appUserSyncService.sync(CurrentUser.from(jwt, authentication));
        return stressSeriesService.start(request, triggeredBy.getId());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Serie d'essais : essais, pause en cours et point de rupture consolide")
    @ApiResponse(responseCode = "404", description = "Serie introuvable")
    public StressSeriesResponse get(@PathVariable UUID id) {
        return stressSeriesService.get(id);
    }

    @GetMapping("/{id}/report/pdf")
    @Operation(summary = "Exporter le rapport d'une serie terminee en PDF",
            description = "Point de rupture consolide, essais et methode. 409 tant que la serie est en cours.")
    @ApiResponse(responseCode = "200", description = "Fichier PDF", content = @Content(mediaType = "application/pdf"))
    @ApiResponse(responseCode = "409", description = "Serie en cours")
    public ResponseEntity<byte[]> exportPdf(@PathVariable UUID id) {
        StressSeriesResponse series = stressSeriesService.get(id);
        try {
            ReportPdfService.PdfFile file = stressSeriesService.exportPdf(id, java.time.ZoneId.systemDefault());
            auditLogService.record(AuditEvent.of(AuditAction.EXPORT_REPORT, AuditModule.EXECUTION)
                    .target(AuditTargetType.SCENARIO, series.scenarioId(), series.scenarioName())
                    .description("Rapport de la série d'essais exporté au format PDF"));
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.fileName() + "\"")
                    .body(file.content());
        } catch (RuntimeException e) {
            auditLogService.record(AuditEvent.of(AuditAction.EXPORT_REPORT, AuditModule.EXECUTION)
                    .target(AuditTargetType.SCENARIO, series.scenarioId(), series.scenarioName())
                    .description("Export PDF du rapport de la série impossible").failed(e));
            throw e;
        }
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Annuler une serie d'essais", description = "Annule l'essai en cours, ou interrompt la pause et libere le serveur.")
    @ApiResponse(responseCode = "409", description = "Serie deja terminee")
    public StressSeriesResponse cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        AppUser user = appUserSyncService.sync(CurrentUser.from(jwt, authentication));
        return stressSeriesService.cancel(id, user.getUsername());
    }
}
