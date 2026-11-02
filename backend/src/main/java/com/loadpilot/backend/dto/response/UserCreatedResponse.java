package com.loadpilot.backend.dto.response;

/** Compte cree et facon dont le mot de passe a ete fixe. */
public record UserCreatedResponse(
        UserSummaryResponse user,
        boolean invitationSent,
        boolean passwordSet
) {
}
