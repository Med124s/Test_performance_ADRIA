package com.loadpilot.backend.dto.request;

import com.loadpilot.backend.enums.ScheduleNotifyMode;
import com.loadpilot.backend.enums.ScheduleType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/**
 * Reutilise pour la creation ET la modification (meme convention que
 * ScenarioRequest, voir ScenarioController) - jamais deux DTOs quasi
 * identiques.
 *
 * Validation croisee (cronExpression requis SSI RECURRING_CRON, runAt
 * requis et futur SSI ONE_TIME, endAt apres la premiere occurrence) faite
 * dans ScheduleNextRunCalculator (une @NotNull conditionnelle n'est pas
 * exprimable proprement avec les annotations Bean Validation standard).
 *
 * "endAt" (optionnel, recurrente seulement) et "notifyMode" (null = ALWAYS)
 * peuvent etre omis : les clients existants restent valides.
 */
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