package com.loadpilot.backend.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;

/**
 * PATCH /api/profile : preferences reellement possedees par l'utilisateur, chacune facultative
 * (null = inchangee) : fuseau horaire (identifiant IANA) et apparence (LIGHT, DARK, SYSTEM).
 * Jamais username/name/email, proprietes du systeme d'authentification.
 */
public record ProfileUpdateRequest(
        String timezone,
        @Pattern(regexp = "LIGHT|DARK|SYSTEM", message = "Apparence attendue : LIGHT, DARK ou SYSTEM.")
        String theme
) {
    @AssertTrue(message = "Le fuseau horaire est obligatoire (identifiant IANA, ex: Africa/Casablanca).")
    public boolean isTimezoneValidWhenPresent() {
        return timezone == null ? theme != null : !timezone.isBlank();
    }
}
