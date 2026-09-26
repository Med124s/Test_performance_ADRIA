package com.loadpilot.backend.dto.request;

import jakarta.validation.constraints.NotNull;
import com.loadpilot.backend.enums.AppRole;

/** "role" doit etre l'un des 3 AppRole reels - valide par @NotNull + le
 * type enum lui-meme (Jackson rejette toute valeur hors enum avec un 400
 * via HttpMessageNotReadableException, jamais un role Keycloak arbitraire). */
public record UpdateUserRoleRequest(
        @NotNull(message = "Le role est obligatoire.")
        AppRole role
) {
}
