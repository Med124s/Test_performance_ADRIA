package com.loadpilot.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Nouveau mot de passe defini par un administrateur. Transmis a Keycloak puis
 * oublie : jamais enregistre, jamais renvoye, jamais journalise (toString masque). */
public record SetPasswordRequest(
        @NotBlank(message = "Le mot de passe est obligatoire.")
        @Size(max = 128, message = "Le mot de passe ne doit pas dépasser 128 caractères.")
        String password,

        @NotNull(message = "Indiquez si le mot de passe est temporaire.")
        Boolean temporary
) {

    @Override
    public String toString() {
        return "SetPasswordRequest[password=***, temporary=" + temporary + "]";
    }
}
