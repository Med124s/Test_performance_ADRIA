package com.loadpilot.backend.service;

import com.loadpilot.backend.dto.request.AuditSettingsRequest;
import com.loadpilot.backend.dto.response.AuditSettingsResponse;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.service.audit.AuditEvent;
import com.loadpilot.backend.service.audit.AuditTargetType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Reglages du journal d'audit (Parametres) ; chaque modification est elle-meme journalisee (avant -> apres). */
@Service
@RequiredArgsConstructor
public class AuditSettingsService {

    private final AppSettingsService settings;
    private final AuditLogService auditLogService;

    public AuditSettingsResponse get() {
        return new AuditSettingsResponse(settings.logReads(), settings.retentionDays());
    }

    public AuditSettingsResponse update(AuditSettingsRequest request, String updatedBy) {
        AuditSettingsResponse before = get();
        settings.set(AppSettingsService.AUDIT_LOG_READS, String.valueOf(request.logReads()), updatedBy);
        settings.set(AppSettingsService.AUDIT_RETENTION_DAYS, String.valueOf(request.retentionDays()), updatedBy);
        AuditSettingsResponse after = get();
        auditLogService.record(AuditEvent.of(AuditAction.UPDATE, AuditModule.SETTINGS)
                .target(AuditTargetType.SETTINGS, "audit", "Journal d’audit")
                .change("Journaliser aussi les consultations", before.logReads(), after.logReads())
                .change("Durée de conservation", before.retentionDays() + " jours", after.retentionDays() + " jours")
                .description("Réglages du journal d’audit modifiés"));
        return after;
    }
}
