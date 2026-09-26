package com.loadpilot.backend.service.keycloak;

import com.loadpilot.backend.enums.AppRole;
import java.util.List;
import java.util.Set;

/**
 * Acces reel a l'Admin REST API de Keycloak (realm loadpilot), via le
 * compte de service dedie "loadpilot-backend-admin" (Phase 25) - jamais le
 * compte admin Keycloak lui-meme, jamais un token d'administration
 * transmis par le frontend (voir HttpKeycloakAdminClient).
 */
public interface KeycloakAdminClient {

    /** Tous les utilisateurs reels du realm (voir KeycloakUserRecord). */
    List<KeycloakUserRecord> listUsers();

    /** Un utilisateur reel par id Keycloak - leve ResourceNotFoundException si absent. */
    KeycloakUserRecord getUser(String userId);

    /** Les noms de roles realm REELLEMENT assignes a cet utilisateur (peut
     * inclure des roles Keycloak par defaut hors perimetre applicatif,
     * ex: offline_access - jamais filtre ici, le filtrage sur les 3
     * AppRole se fait cote appelant). */
    Set<String> getUserRealmRoleNames(String userId);

    /** Active/desactive reellement le compte (champ "enabled" Keycloak). */
    void setUserEnabled(String userId, boolean enabled);

    /** Retire tout role parmi les 3 AppRole actuellement assigne a
     * l'utilisateur, puis assigne reellement le nouveau (Keycloak permet
     * plusieurs roles simultanes, mais le modele applicatif LoadPilot est
     * "un seul des 3 roles par utilisateur", coherent avec le provisionnement
     * initial de la Phase 16.5). */
    void replaceUserAppRole(String userId, AppRole newRole);
}
