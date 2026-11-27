package com.loadpilot.backend.dto.request;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Lancement d'une serie d'essais de stress : le meme scenario, "runs" fois de suite (3 ou 5). */
public record StressSeriesRequest(
        @NotNull(message = "L'identifiant du scenario est obligatoire.")
        UUID scenarioId,

        @NotNull(message = "Le nombre d'essais est obligatoire.")
        Integer runs
) {
}
