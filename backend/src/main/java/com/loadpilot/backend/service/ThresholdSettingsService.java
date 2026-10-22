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
        return new ThresholdSettingsResponse(settings.defaultMaxErrorRate(), settings.defaultMaxP95Ms(), settings.regressionPercent(),
                settings.defaultErrorBrakeEnabled(), settings.defaultErrorBrakeRate(), settings.defaultErrorBrakeWindow(),
                settings.defaultThrottleOn429(), settings.defaultThrottleDelay(), settings.defaultThrottleRetries(),
                settings.defaultRequestDetailMode());
    }

    public ThresholdSettingsResponse update(ThresholdSettingsRequest request, String updatedBy) {
        ThresholdSettingsResponse before = get();
        settings.set(AppSettingsService.DEFAULT_MAX_ERROR_RATE, String.valueOf(request.maxErrorRatePercent()), updatedBy);
        settings.set(AppSettingsService.DEFAULT_MAX_P95_MS, String.valueOf(request.maxP95Ms()), updatedBy);
        settings.set(AppSettingsService.REGRESSION_PERCENT, String.valueOf(request.regressionPercent()), updatedBy);
        // Reglages des nouveaux scenarios : null = inchange.
        setIfPresent(AppSettingsService.DEFAULT_ERROR_BRAKE_ENABLED, request.errorBrakeEnabled(), updatedBy);
        setIfPresent(AppSettingsService.DEFAULT_ERROR_BRAKE_RATE, request.errorBrakeRatePercent(), updatedBy);
        setIfPresent(AppSettingsService.DEFAULT_ERROR_BRAKE_WINDOW, request.errorBrakeWindowSeconds(), updatedBy);
        setIfPresent(AppSettingsService.DEFAULT_THROTTLE_ON_429, request.throttleOn429(), updatedBy);
        setIfPresent(AppSettingsService.DEFAULT_THROTTLE_DELAY, request.throttleDefaultDelaySeconds(), updatedBy);
        setIfPresent(AppSettingsService.DEFAULT_THROTTLE_RETRIES, request.throttleMaxRetries(), updatedBy);
        setIfPresent(AppSettingsService.DEFAULT_REQUEST_DETAIL_MODE, request.requestDetailMode() == null ? null : request.requestDetailMode().name(),
                updatedBy);
        ThresholdSettingsResponse after = get();
        auditLogService.record(AuditEvent.of(AuditAction.UPDATE, AuditModule.SETTINGS)
                .target(AuditTargetType.SETTINGS, "thresholds", "Seuils par défaut")
                .change("Taux d’erreurs maximal proposé", percent(before.maxErrorRatePercent()), percent(after.maxErrorRatePercent()))
                .change("P95 maximal proposé", p95(before.maxP95Ms()), p95(after.maxP95Ms()))
                .change("Seuil de régression", percent(before.regressionPercent()), percent(after.regressionPercent()))
                .change("Frein automatique proposé", onOff(before.errorBrakeEnabled()), onOff(after.errorBrakeEnabled()))
                .change("Taux du frein proposé", percent(before.errorBrakeRatePercent()), percent(after.errorBrakeRatePercent()))
                .change("Fenêtre du frein proposée", before.errorBrakeWindowSeconds() + " s", after.errorBrakeWindowSeconds() + " s")
                .change("Ralentissement sur 429 proposé", onOff(before.throttleOn429()), onOff(after.throttleOn429()))
                .change("Attente sans Retry-After proposée", before.throttleDefaultDelaySeconds() + " s", after.throttleDefaultDelaySeconds() + " s")
                .change("Rejeux après un 429 proposés", String.valueOf(before.throttleMaxRetries()), String.valueOf(after.throttleMaxRetries()))
                .change("Détail des requêtes proposé", detailMode(before.requestDetailMode()), detailMode(after.requestDetailMode()))
                .description("Seuils par défaut modifiés"));
        return after;
    }

    private void setIfPresent(String key, Object value, String updatedBy) {
        if (value != null) settings.set(key, String.valueOf(value), updatedBy);
    }

    static String onOff(boolean on) {
        return on ? "Activé" : "Désactivé";
    }

    static String detailMode(com.loadpilot.backend.enums.RequestDetailMode mode) {
        return switch (mode) {
            case NONE -> "Aucun";
            case FAILURES -> "Échecs";
            case FAILURES_AND_SAMPLE -> "Échecs + échantillon";
        };
    }

    static String percent(double value) {
        return DashboardRules.format(value) + " %";
    }

    static String p95(long ms) {
        return ms <= 0 ? "Aucun" : DashboardRules.format(ms) + " ms";
    }
}
