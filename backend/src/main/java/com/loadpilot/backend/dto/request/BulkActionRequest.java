package com.loadpilot.backend.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Action groupee sur plusieurs applications.
 * bulk-archive : mode ARCHIVE (defaut) ou RESTORE.
 * bulk-delete  : mode ARCHIVE (defaut) ou PERMANENT (SUPER_ADMIN uniquement).
 */
public record BulkActionRequest(
        @NotEmpty(message = "Selectionnez au moins une application.")
        @Size(max = 100, message = "100 applications au maximum par action groupee.")
        List<UUID> ids,
        String mode
) {
}
