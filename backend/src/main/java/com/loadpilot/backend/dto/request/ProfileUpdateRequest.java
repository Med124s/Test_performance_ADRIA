package com.loadpilot.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;

/**
 * PATCH /api/profile : preferences reellement possedees par l'utilisateur, chacune facultative
 * (null = inchangee) : fuseau horaire (identifiant IANA) et apparence (LIGHT, DARK, SYSTEM).
 *
 * Jamais l'identite : nom, prenom et e-mail se modifient par PUT /api/profile/identity (Super
 * administrateur seulement) ; nom d'utilisateur, role et etat du compte ne se modifient jamais
 * depuis son profil. Ces champs sont lus ici uniquement pour REFUSER la demande (403) au lieu de
 * les ignorer en silence.
 */
public record ProfileUpdateRequest(
        String timezone,
        @Pattern(regexp = "LIGHT|DARK|SYSTEM", message = "Apparence attendue : LIGHT, DARK ou SYSTEM.")
        String theme,
        @JsonProperty("name") Object name,
        @JsonProperty("firstName") Object firstName,
        @JsonProperty("lastName") Object lastName,
        @JsonProperty("email") Object email,
        @JsonProperty("username") Object username,
        @JsonProperty("role") Object role,
        @JsonProperty("roles") Object roles,
        @JsonProperty("appRole") Object appRole,
        @JsonProperty("enabled") Object enabled
) {
    public ProfileUpdateRequest(String timezone, String theme) {
        this(timezone, theme, null, null, null, null, null, null, null, null, null);
    }

    /** Nom, prenom ou e-mail demandes. */
    public boolean touchesIdentity() {
        return name != null || firstName != null || lastName != null || email != null;
    }

    /** Nom d'utilisateur, role ou etat du compte demandes. */
    public boolean touchesProtectedFields() {
        return username != null || role != null || roles != null || appRole != null || enabled != null;
    }

    @AssertTrue(message = "Le fuseau horaire est obligatoire (identifiant IANA, ex: Africa/Casablanca).")
    public boolean isTimezoneValidWhenPresent() {
        // Une demande d'identite ou de champ protege est refusee plus loin (403), jamais en 400.
        if (touchesIdentity() || touchesProtectedFields()) return true;
        return timezone == null ? theme != null : !timezone.isBlank();
    }
}
