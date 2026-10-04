package com.loadpilot.backend.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Action groupee sur des scenarios (bulk-archive, bulk-restore, bulk-delete, bulk-status,
 * bulk-deletion-impact, bulk-resume-schedules). "status" : ACTIVE ou INACTIVE, pour bulk-status uniquement.
 */
public record ScenarioBulkRequest(
        @NotEmpty(message = "Sélectionnez au moins un scénario.")
        @Size(max = 100, message = "100 scénarios au maximum par action groupée.")
        List<UUID> ids,
        String status
) {
}
