package com.loadpilot.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * PUT /api/profile/identity (Super administrateur uniquement) : prenom, nom et e-mail du compte
 * connecte, enregistres dans le systeme d'authentification. Jamais le nom d'utilisateur, le role
 * ni l'etat du compte : ces champs, s'ils sont envoyes, font refuser la demande (403).
 */
public record ProfileIdentityRequest(
        @NotBlank(message = "Le prénom est obligatoire.")
        @Size(max = 100, message = "Le prénom ne doit pas dépasser 100 caractères.")
        String firstName,
        @NotBlank(message = "Le nom est obligatoire.")
        @Size(max = 100, message = "Le nom ne doit pas dépasser 100 caractères.")
        String lastName,
        @NotBlank(message = "L’e-mail est obligatoire.")
        @Email(message = "L’e-mail n’est pas valide.")
        @Size(max = 254, message = "L’e-mail ne doit pas dépasser 254 caractères.")
        String email,
        @JsonProperty("username") Object username,
        @JsonProperty("role") Object role,
        @JsonProperty("roles") Object roles,
        @JsonProperty("appRole") Object appRole,
        @JsonProperty("enabled") Object enabled
) {
    public ProfileIdentityRequest(String firstName, String lastName, String email) {
        this(firstName, lastName, email, null, null, null, null, null);
    }

    /** Nom d'utilisateur, role ou etat du compte demandes : jamais modifiables depuis son profil. */
    public boolean touchesProtectedFields() {
        return username != null || role != null || roles != null || appRole != null || enabled != null;
    }
}
