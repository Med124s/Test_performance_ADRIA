package com.loadpilot.backend.dto.response;

import java.util.Set;

/**
 * Reponse de GET /api/profile - identite et roles de l'utilisateur
 * authentifie, jamais le Jwt lui-meme ni une revendication sensible.
 *
 * "id" = sujet Keycloak ("sub") : c'est l'identifiant de l'utilisateur tel
 * que connu par l'IdP, pas l'id technique local de la table app_user (une
 * simple projection, jamais la source de verite de l'identite).
 */
public record ProfileResponse(
        String id,
        String username,
        String name,
        String email,
        Set<String> roles,
        /** P1-D — preference REELLE persistee (voir entity.AppUser.timezone),
         * jamais issue du Jwt (Keycloak n'a aucune notion de ce champ).
         * Null si l'utilisateur n'a jamais choisi de fuseau. */
        String timezone
) {
}
