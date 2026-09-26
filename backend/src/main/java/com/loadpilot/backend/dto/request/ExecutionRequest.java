package com.loadpilot.backend.dto.request;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ExecutionRequest(
        @NotNull(message = "L'identifiant du scenario est obligatoire.")
        UUID scenarioId
) {
}
