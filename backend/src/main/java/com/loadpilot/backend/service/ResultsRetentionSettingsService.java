package com.loadpilot.backend.service;

import com.loadpilot.backend.dto.request.ResultsRetentionSettingsRequest;
import com.loadpilot.backend.dto.response.ResultsRetentionSettingsResponse;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.exception.BadRequestException;
import com.loadpilot.backend.service.audit.AuditEvent;
import com.loadpilot.backend.service.audit.AuditTargetType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Durees de conservation des resultats detailles (Parametres) ; chaque modification est journalisee (avant -> apres). */
@Service
@RequiredArgsConstructor
public class ResultsRetentionSettingsService {

    static final String DETAILS_LONGER = "Le détail des requêtes ne peut pas être conservé plus longtemps que les résultats.";

    private final AppSettingsService settings;
    private final AuditLogService auditLogService;

    public ResultsRetentionSettingsResponse get() {
        return new ResultsRetentionSettingsResponse(settings.resultsRetentionDays(), settings.requestDetailsRetentionDays());
    }

    public ResultsRetentionSettingsResponse update(ResultsRetentionSettingsRequest request, String updatedBy) {
        if (request.requestDetailsDays() > request.resultsDays()) {
            throw new BadRequestException(DETAILS_LONGER);
        }
        ResultsRetentionSettingsResponse before = get();
        settings.set(AppSettingsService.RESULTS_RETENTION_DAYS, String.valueOf(request.resultsDays()), updatedBy);
        settings.set(AppSettingsService.REQUEST_DETAILS_RETENTION_DAYS, String.valueOf(request.requestDetailsDays()), updatedBy);
        ResultsRetentionSettingsResponse after = get();
        auditLogService.record(AuditEvent.of(AuditAction.UPDATE, AuditModule.SETTINGS)
                .target(AuditTargetType.SETTINGS, "results-retention", "Conservation des résultats")
                .change("Résultats détaillés", before.resultsDays() + " jours", after.resultsDays() + " jours")
                .change("En-têtes et corps des requêtes", before.requestDetailsDays() + " jours", after.requestDetailsDays() + " jours")
                .description("Durées de conservation des résultats modifiées"));
        return after;
    }
}
