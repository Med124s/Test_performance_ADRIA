package com.loadpilot.backend.service;

import com.loadpilot.backend.dto.request.ThresholdSettingsRequest;
import com.loadpilot.backend.dto.response.ThresholdSettingsResponse;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.service.audit.AuditEvent;
import com.loadpilot.backend.service.audit.AuditTargetType;
import com.loadpilot.backend.service.impl.DashboardRules;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Seuils par defaut (Parametres > Systeme) ; lus par la creation des scenarios et par toutes les
 * comparaisons de P95 (Dashboard, listes, planification, notifications). Chaque modification est
 * journalisee (avant -> apres).
 */
@Service
@RequiredArgsConstructor
public class ThresholdSettingsService {

    private final AppSettingsService settings;
    private final AuditLogService auditLogService;

    public ThresholdSettingsResponse get() {
        return new ThresholdSettingsResponse(settings.defaultMaxErrorRate(), settings.defaultMaxP95Ms(), settings.regressionPercent());
    }

    public ThresholdSettingsResponse update(ThresholdSettingsRequest request, String updatedBy) {
        ThresholdSettingsResponse before = get();
        settings.set(AppSettingsService.DEFAULT_MAX_ERROR_RATE, String.valueOf(request.maxErrorRatePercent()), updatedBy);
        settings.set(AppSettingsService.DEFAULT_MAX_P95_MS, String.valueOf(request.maxP95Ms()), updatedBy);
        settings.set(AppSettingsService.REGRESSION_PERCENT, String.valueOf(request.regressionPercent()), updatedBy);
        ThresholdSettingsResponse after = get();
        auditLogService.record(AuditEvent.of(AuditAction.UPDATE, AuditModule.SETTINGS)
                .target(AuditTargetType.SETTINGS, "thresholds", "Seuils par défaut")
                .change("Taux d’erreurs maximal proposé", percent(before.maxErrorRatePercent()), percent(after.maxErrorRatePercent()))
                .change("P95 maximal proposé", p95(before.maxP95Ms()), p95(after.maxP95Ms()))
                .change("Seuil de régression", percent(before.regressionPercent()), percent(after.regressionPercent()))
                .description("Seuils par défaut modifiés"));
        return after;
    }

    static String percent(double value) {
        return DashboardRules.format(value) + " %";
    }

    static String p95(long ms) {
        return ms <= 0 ? "Aucun" : DashboardRules.format(ms) + " ms";
    }
}
