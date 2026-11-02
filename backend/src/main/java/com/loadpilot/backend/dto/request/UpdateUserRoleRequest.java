package com.loadpilot.backend.dto.request;

import jakarta.validation.constraints.NotNull;
import com.loadpilot.backend.enums.AppRole;

/** Nouveau role applicatif d'un utilisateur. */
public record UpdateUserRoleRequest(
        @NotNull(message = "Le role est obligatoire.")
        AppRole role
) {
}
