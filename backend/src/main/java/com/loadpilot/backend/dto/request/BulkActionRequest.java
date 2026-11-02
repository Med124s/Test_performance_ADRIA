package com.loadpilot.backend.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Action groupee sur des applications : ARCHIVE / RESTORE, ou PERMANENT (Super administrateur) pour la suppression. */
public record BulkActionRequest(
        @NotEmpty(message = "Selectionnez au moins une application.")
        @Size(max = 100, message = "100 applications au maximum par action groupee.")
        List<UUID> ids,
        String mode
) {
}
