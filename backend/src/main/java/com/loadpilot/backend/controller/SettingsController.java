package com.loadpilot.backend.controller;

import com.loadpilot.backend.dto.request.AuditSettingsRequest;
import com.loadpilot.backend.dto.response.AuditSettingsResponse;
import com.loadpilot.backend.service.AuditSettingsService;
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
@Tag(name = "Settings", description = "Reglages modifiables (journal d'audit) - reserve a SUPER_ADMIN")
public class SettingsController {

    private final AuditSettingsService auditSettingsService;

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
}
