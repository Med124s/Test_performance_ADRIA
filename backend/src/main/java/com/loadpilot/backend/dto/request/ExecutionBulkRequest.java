package com.loadpilot.backend.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Selection d'executions pour une action groupee (suppression, impact). */
public record ExecutionBulkRequest(
        @NotEmpty(message = "Sélectionnez au moins une exécution.")
        @Size(max = 100, message = "100 exécutions au maximum par action groupée.")
        List<UUID> ids
) {
}
