package com.loadpilot.backend.service.keycloak;

import com.loadpilot.backend.enums.AppRole;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    /** Prenom, nom et e-mail d'un compte (le reste du compte est conserve tel quel) ; ConflictException si l'e-mail est deja pris. */
    void updateUserIdentity(String userId, String firstName, String lastName, String email);

    /** Retire tout role parmi les 3 AppRole actuellement assigne a
     * l'utilisateur, puis assigne reellement le nouveau (Keycloak permet
     * plusieurs roles simultanes, mais le modele applicatif LoadPilot est
     * "un seul des 3 roles par utilisateur", coherent avec le provisionnement
     * initial de la Phase 16.5). */
    void replaceUserAppRole(String userId, AppRole newRole);

    /** Date de la derniere connexion connue de chaque utilisateur (id Keycloak
     * -> instant), lue dans les evenements LOGIN du realm (necessite
     * eventsEnabled sur le realm et le role realm-management "view-events"
     * pour le compte de service). Un utilisateur absent de la map n'a aucune
     * connexion enregistree dans la periode de conservation des evenements. */
    Map<String, Instant> lastLoginByUser();

    /** Cree reellement le compte (active, sans mot de passe) et renvoie son id.
     * Leve ConflictException si le nom d'utilisateur ou l'e-mail existe deja. */
    String createUser(NewKeycloakUser user);

    /** Supprime reellement le compte (utilise seulement pour annuler une
     * creation dont l'attribution du role a echoue). */
    void deleteUser(String userId);

    /** Envoie par e-mail (SMTP du realm) un lien Keycloak pour executer des
     * actions, ex: UPDATE_PASSWORD - le mot de passe est saisi sur Keycloak,
     * jamais transmis a LoadPilot. */
    void sendActionsEmail(String userId, List<String> actions);

    /** Definit reellement le mot de passe (temporary = changement impose a la
     * prochaine connexion). Leve BadRequestException avec un message francais
     * si la politique du realm le refuse. Le mot de passe n'est ni conserve ni
     * journalise. */
    void setPassword(String userId, String password, boolean temporary);

    /** Une page d'evenements de securite du realm (connexion, echec, blocage,
     * deconnexion, mot de passe), du plus recent au plus ancien. */
    List<KeycloakSecurityEvent> securityEvents(int first, int max);

    /** Politique de mot de passe du realm, au format Keycloak, ex.
     * "length(8) and digits(1)" ; chaine vide si aucune. */
    String getPasswordPolicy();

    /** Sessions ouvertes d'un utilisateur (API d'administration : sans navigateur ni systeme). */
    default List<KeycloakSession> userSessions(String userId) {
        return List.of();
    }

    /**
     * Sessions de l'utilisateur via l'API « compte » de Keycloak, avec SON jeton (navigateur, systeme,
     * session courante). Leve KeycloakAdminException si l'API est indisponible.
     */
    default List<KeycloakSession> accountSessions(String userAccessToken) {
        throw new com.loadpilot.backend.exception.KeycloakAdminException("Sessions indisponibles.");
    }

    /** Ferme toutes les sessions d'un utilisateur (tous les appareils). */
    default void logoutUser(String userId) {
        throw new com.loadpilot.backend.exception.KeycloakAdminException("Déconnexion indisponible.");
    }

    /** Dernieres connexions reussies d'un utilisateur, de la plus recente a la plus ancienne. */
    default List<KeycloakLogin> recentLogins(String userId, int max) {
        return List.of();
    }

    /** Serveur e-mail du realm (null si aucun), jamais d'identifiant ni de mot de passe. */
    default KeycloakSmtpInfo smtpInfo() {
        return null;
    }
}
