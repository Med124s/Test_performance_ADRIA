package com.loadpilot.backend.service;

import com.loadpilot.backend.dto.request.LoadDefaultsRequest;
import com.loadpilot.backend.dto.response.LoadDefaultsResponse;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.exception.BadRequestException;
import com.loadpilot.backend.service.audit.AuditEvent;
import com.loadpilot.backend.service.audit.AuditTargetType;
import com.loadpilot.backend.service.impl.DashboardRules;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Charge par defaut proposee aux nouveaux scenarios (Parametres > Systeme). Les scenarios
 * existants ne sont jamais modifies. Memes regles et memes messages que l'etape « Charge &
 * resume » de l'assistant (wizardModel.validateLoad) ; chaque modification est journalisee
 * (avant -> apres). Valeurs initiales : celles de l'assistant (1 utilisateur, 1 passage).
 */
@Service
@RequiredArgsConstructor
public class LoadDefaultsService {

    static final int DEFAULT_VIRTUAL_USERS = 1;
    static final int DEFAULT_THINK_TIME_MS = 0;
    static final int DEFAULT_RAMP_UP_SECONDS = 0;
    static final Integer DEFAULT_DURATION_SECONDS = null;
    static final Integer DEFAULT_ITERATIONS = 1;
    static final Integer DEFAULT_TARGET_RPS = null;

    private final AppSettingsService settings;
    private final AuditLogService auditLogService;

    private final ExecutionLimitsService executionLimits;

    /** Limite reellement applicable a un test. */
    public int maxVirtualUsers() {
        return executionLimits.maxVirtualUsersPerTest();
    }

    public LoadDefaultsResponse get() {
        return new LoadDefaultsResponse(
                orDefault(settings.getOptionalInt(AppSettingsService.LOAD_VIRTUAL_USERS, DEFAULT_VIRTUAL_USERS), DEFAULT_VIRTUAL_USERS),
                orDefault(settings.getOptionalInt(AppSettingsService.LOAD_THINK_TIME_MS, DEFAULT_THINK_TIME_MS), DEFAULT_THINK_TIME_MS),
                orDefault(settings.getOptionalInt(AppSettingsService.LOAD_RAMP_UP_SECONDS, DEFAULT_RAMP_UP_SECONDS), DEFAULT_RAMP_UP_SECONDS),
                settings.getOptionalInt(AppSettingsService.LOAD_DURATION_SECONDS, DEFAULT_DURATION_SECONDS),
                settings.getOptionalInt(AppSettingsService.LOAD_ITERATIONS, DEFAULT_ITERATIONS),
                settings.getOptionalInt(AppSettingsService.LOAD_TARGET_RPS, DEFAULT_TARGET_RPS),
                maxVirtualUsers(), 0, 0, 0, List.of()).withLimits(executionLimits.loadLimits());
    }

    public LoadDefaultsResponse update(LoadDefaultsRequest request, String updatedBy) {
        List<String> errors = validate(request, executionLimits.loadLimits());
        if (!errors.isEmpty()) {
            throw new BadRequestException(String.join(" ", errors));
        }
        LoadDefaultsResponse before = get();
        settings.set(AppSettingsService.LOAD_VIRTUAL_USERS, text(request.virtualUsers()), updatedBy);
        settings.set(AppSettingsService.LOAD_THINK_TIME_MS, text(request.thinkTimeMs()), updatedBy);
        settings.set(AppSettingsService.LOAD_RAMP_UP_SECONDS, text(request.rampUpSeconds()), updatedBy);
        settings.set(AppSettingsService.LOAD_DURATION_SECONDS, text(request.durationSeconds()), updatedBy);
        settings.set(AppSettingsService.LOAD_ITERATIONS, text(request.iterations()), updatedBy);
        settings.set(AppSettingsService.LOAD_TARGET_RPS, text(request.targetRps()), updatedBy);
        LoadDefaultsResponse after = get();
        auditLogService.record(AuditEvent.of(AuditAction.UPDATE, AuditModule.SETTINGS)
                .target(AuditTargetType.SETTINGS, "load-defaults", "Charge par défaut")
                .change("Utilisateurs virtuels", n(before.virtualUsers()), n(after.virtualUsers()))
                .change("Think time", before.thinkTimeMs() + " ms", after.thinkTimeMs() + " ms")
                .change("Ramp-up", before.rampUpSeconds() + " s", after.rampUpSeconds() + " s")
                .change("Durée", optional(before.durationSeconds(), " s", "Sans limite de temps"), optional(after.durationSeconds(), " s", "Sans limite de temps"))
                .change("Itérations", optional(before.iterations(), "", "Aucune"), optional(after.iterations(), "", "Aucune"))
                .change("Débit cible", optional(before.targetRps(), " req/s", "Illimité"), optional(after.targetRps(), " req/s", "Illimité"))
                .description("Charge par défaut des nouveaux scénarios modifiée"));
        return after;
    }

    /**
     * Memes regles et memes messages que wizardModel.validateLoad (etape « Charge & resume ») :
     * entiers, minimums, utilisateurs virtuels <= limite applicable, ramp-up inferieur a la duree.
     */
    public static List<String> validate(LoadDefaultsRequest r, com.loadpilot.backend.service.validation.LoadLimits limits) {
        return com.loadpilot.backend.service.validation.LoadRules.validate(r.virtualUsers(), r.thinkTimeMs(), r.rampUpSeconds(),
                r.durationSeconds(), r.iterations(), r.targetRps(), limits);
    }

    private static int orDefault(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static String text(Integer value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String n(int value) {
        return DashboardRules.format(value);
    }

    private static String optional(Integer value, String unit, String empty) {
        return value == null ? empty : DashboardRules.format(value) + unit;
    }
}
