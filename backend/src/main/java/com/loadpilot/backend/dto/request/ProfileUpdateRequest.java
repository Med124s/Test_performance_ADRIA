package com.loadpilot.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;

/** Preferences du profil (fuseau horaire IANA, apparence LIGHT / DARK / SYSTEM) ; null = inchange. */
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
        if (touchesIdentity() || touchesProtectedFields()) return true;
        return timezone == null ? theme != null : !timezone.isBlank();
    }
}
