package com.loadpilot.backend.controller;

import com.loadpilot.backend.dto.request.AuditSettingsRequest;
import com.loadpilot.backend.dto.response.AuditSettingsResponse;
import com.loadpilot.backend.service.AuditSettingsService;
import com.loadpilot.backend.dto.request.ResultsRetentionSettingsRequest;
import com.loadpilot.backend.dto.response.ResultsRetentionSettingsResponse;
import com.loadpilot.backend.service.ResultsRetentionSettingsService;
import com.loadpilot.backend.dto.request.ThresholdSettingsRequest;
import com.loadpilot.backend.dto.response.StorageInfoResponse;
import com.loadpilot.backend.dto.response.ThresholdSettingsResponse;
import com.loadpilot.backend.service.StorageInfoService;
import com.loadpilot.backend.service.ThresholdSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reglages modifiables de LoadPilot (Parametres > Systeme) - reserve a SUPER_ADMIN. */
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Tag(name = "Settings", description = "Reglages modifiables (journal d'audit, conservation des resultats) - reserve a SUPER_ADMIN")
public class SettingsController {

    private final AuditSettingsService auditSettingsService;
    private final ResultsRetentionSettingsService retentionSettingsService;
    private final ThresholdSettingsService thresholdSettingsService;
    private final StorageInfoService storageInfoService;
    private final com.loadpilot.backend.service.ExecutionLimitsService executionLimitsService;

    @GetMapping("/audit")
    @Operation(summary = "Reglages du journal d'audit")
    public AuditSettingsResponse auditSettings() {
        return auditSettingsService.get();
    }

    @PutMapping("/audit")
    @Operation(summary = "Modifier les reglages du journal d'audit",
            description = "logReads : journaliser aussi les consultations (desactive par defaut). retentionDays : 30 a 3650 jours (365 par defaut).")
    public AuditSettingsResponse updateAuditSettings(@Valid @RequestBody AuditSettingsRequest request, @AuthenticationPrincipal Jwt jwt) {
        String by = jwt.getClaimAsString("preferred_username");
        return auditSettingsService.update(request, by != null ? by : jwt.getSubject());
    }

    @GetMapping("/results-retention")
    @Operation(summary = "Durees de conservation des resultats detailles")
    public ResultsRetentionSettingsResponse resultsRetention() {
        return retentionSettingsService.get();
    }

    @PutMapping("/results-retention")
    @Operation(summary = "Modifier les durees de conservation des resultats detailles",
            description = "resultsDays : 7 a 3650 jours (90 par defaut) ; requestDetailsDays : 1 a 3650 jours (30 par defaut), "
                    + "jamais plus que resultsDays. Purge automatique chaque nuit ; executions conservees et en cours jamais purgees.")
    public ResultsRetentionSettingsResponse updateResultsRetention(@Valid @RequestBody ResultsRetentionSettingsRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String by = jwt.getClaimAsString("preferred_username");
        return retentionSettingsService.update(request, by != null ? by : jwt.getSubject());
    }

    /** Lu par toutes les pages qui comparent des P95 (seuil de regression) et par l'assistant de scenario. */
    @GetMapping("/thresholds")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Seuils par defaut", description = "Taux d'erreurs et P95 maximaux proposes pour les nouveaux scenarios, seuil de regression. Lisible par tout utilisateur connecte.")
    public ThresholdSettingsResponse thresholds() {
        return thresholdSettingsService.get();
    }

    @PutMapping("/thresholds")
    @Operation(summary = "Modifier les seuils par defaut",
            description = "maxErrorRatePercent 0 a 100 ; maxP95Ms 0 (aucun) a 600000 ; regressionPercent 1 a 500. Journalise avec l'avant -> apres.")
    public ThresholdSettingsResponse updateThresholds(@Valid @RequestBody ThresholdSettingsRequest request, @AuthenticationPrincipal Jwt jwt) {
        String by = jwt.getClaimAsString("preferred_username");
        return thresholdSettingsService.update(request, by != null ? by : jwt.getSubject());
    }

    @GetMapping("/storage")
    @Operation(summary = "Taille de la base et purges automatiques", description = "Taille actuelle, date et bilan de la derniere purge, heure de la prochaine (resultats et journal d'audit).")
    public StorageInfoResponse storage() {
        return storageInfoService.info();
    }

    /**
     * Limites d'un test (utilisateurs virtuels, duree, iterations, debit cible) appliquees a cet
     * instant : lues par l'assistant de scenario pour ses plages et sa validation, donc lisibles par
     * tout utilisateur connecte (le detail des limites et leur modification restent reserves).
     */
    @GetMapping("/load-limits")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Limites de charge d'un test", description = "maxVirtualUsers (le plus bas entre « par test » et « au total »), "
            + "maxDurationSeconds, maxIterations, maxTargetRps. Lisible par tout utilisateur connecte.")
    public com.loadpilot.backend.service.validation.LoadLimits loadLimits() {
        return executionLimitsService.loadLimits();
    }

    @GetMapping("/limits")
    @Operation(summary = "Limites des tests", description = "Limites applicatives (modifiables, jamais au-dessus du plafond du serveur) et delais en lecture seule.")
    public com.loadpilot.backend.dto.response.ExecutionLimitsResponse limits() {
        return executionLimitsService.get();
    }

    @PutMapping("/limits")
    @Operation(summary = "Modifier les limites des tests",
            description = "Chaque limite entre 1 et son plafond du serveur. Effet immediat sur les prochains enregistrements, lancements et planifications ; "
                    + "aucun effet sur les executions en cours. Journalise avec l'avant -> apres.")
    public com.loadpilot.backend.dto.response.ExecutionLimitsResponse updateLimits(
            @RequestBody com.loadpilot.backend.dto.request.ExecutionLimitsRequest request, @AuthenticationPrincipal Jwt jwt) {
        String by = jwt.getClaimAsString("preferred_username");
        return executionLimitsService.update(request, by != null ? by : jwt.getSubject());
    }
}
