package com.loadpilot.backend.dto.request;

import com.loadpilot.backend.enums.ScheduleType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * Reutilise pour la creation ET la modification (meme convention que
 * ScenarioRequest, voir ScenarioController) - jamais deux DTOs quasi
 * identiques.
 *
 * Validation croisee (cronExpression requis SSI RECURRING_CRON, runAt
 * requis et futur SSI ONE_TIME) faite dans ScheduledExecutionServiceImpl
 * (une @NotNull conditionnelle n'est pas exprimable proprement avec les
 * annotations Bean Validation standard) - jamais silencieusement ignoree.
 */
public record ScheduledExecutionRequest(
        @NotNull(message = "Le scenario est obligatoire.")
        UUID scenarioId,

        @NotBlank(message = "Le nom de la planification est obligatoire.")
        String name,

        @NotNull(message = "Le type de planification est obligatoire.")
        ScheduleType scheduleType,

        String cronExpression,

        Instant runAt,

        @NotBlank(message = "Le fuseau horaire est obligatoire (ex: Africa/Casablanca, UTC).")
        String timezone
) {
}
