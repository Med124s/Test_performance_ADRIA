package com.loadpilot.backend.dto.request;

import com.loadpilot.backend.enums.ScheduleType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/** Apercu des prochains lancements d'une planification en cours de saisie (rien n'est enregistre). */
public record SchedulePreviewRequest(
        @NotNull(message = "Le type de planification est obligatoire.")
        ScheduleType scheduleType,
        String cronExpression,
        Instant runAt,
        @NotBlank(message = "Le fuseau horaire est obligatoire.")
        String timezone,
        Instant endAt
) {
}