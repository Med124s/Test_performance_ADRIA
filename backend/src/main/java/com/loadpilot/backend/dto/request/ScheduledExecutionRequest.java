package com.loadpilot.backend.dto.request;

import com.loadpilot.backend.enums.ScheduleNotifyMode;
import com.loadpilot.backend.enums.ScheduleType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Creation et modification d'une planification : cronExpression pour RECURRING_CRON, runAt (futur) pour ONE_TIME. */
public record ScheduledExecutionRequest(
        @NotNull(message = "Le scenario est obligatoire.")
        UUID scenarioId,

        @NotBlank(message = "Le nom de la planification est obligatoire.")
        @Size(max = 255, message = "Le nom ne doit pas dépasser 255 caractères.")
        String name,

        @NotNull(message = "Le type de planification est obligatoire.")
        ScheduleType scheduleType,

        String cronExpression,

        Instant runAt,

        @NotBlank(message = "Le fuseau horaire est obligatoire (ex: Africa/Casablanca, UTC).")
        String timezone,

        Instant endAt,

        ScheduleNotifyMode notifyMode
) {

    public ScheduledExecutionRequest(UUID scenarioId, String name, ScheduleType scheduleType, String cronExpression,
                                     Instant runAt, String timezone) {
        this(scenarioId, name, scheduleType, cronExpression, runAt, timezone, null, null);
    }
}