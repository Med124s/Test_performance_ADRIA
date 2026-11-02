package com.loadpilot.backend.dto.request;

import jakarta.validation.constraints.NotNull;

/** Active ou desactive un compte. */
public record UpdateUserStatusRequest(
        @NotNull(message = "Le statut 'enabled' est obligatoire.")
        Boolean enabled
) {
}
