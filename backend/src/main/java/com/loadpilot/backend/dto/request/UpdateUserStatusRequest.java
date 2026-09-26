package com.loadpilot.backend.dto.request;

import jakarta.validation.constraints.NotNull;

/** Active/desactive reellement le compte Keycloak (champ "enabled" reel,
 * jamais un simple champ d'affichage). */
public record UpdateUserStatusRequest(
        @NotNull(message = "Le statut 'enabled' est obligatoire.")
        Boolean enabled
) {
}
