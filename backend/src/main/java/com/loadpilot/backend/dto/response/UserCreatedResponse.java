package com.loadpilot.backend.dto.response;

/** Resultat de POST /api/users : le compte cree (jamais son mot de passe) et
 * comment le mot de passe a ete fixe - passwordSet (defini par
 * l'administrateur) ou invitationSent (lien e-mail effectivement envoye). */
public record UserCreatedResponse(
        UserSummaryResponse user,
        boolean invitationSent,
        boolean passwordSet
) {
}
