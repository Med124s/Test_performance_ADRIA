package com.loadpilot.backend.service.keycloak;

import com.loadpilot.backend.enums.AppRole;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Acces a l'Admin REST API de Keycloak (realm loadpilot). */
public interface KeycloakAdminClient {

    /** Tous les utilisateurs du realm. */
    List<KeycloakUserRecord> listUsers();

    /** Utilisateur par id Keycloak ; ResourceNotFoundException si absent. */
    KeycloakUserRecord getUser(String userId);

    /** Roles realm assignes a l'utilisateur (y compris les roles Keycloak par defaut). */
    Set<String> getUserRealmRoleNames(String userId);

    /** Active ou desactive le compte. */
    void setUserEnabled(String userId, boolean enabled);

    /** Prenom, nom et e-mail d'un compte (le reste du compte est conserve tel quel) ; ConflictException si l'e-mail est deja pris. */
    void updateUserIdentity(String userId, String firstName, String lastName, String email);

    /** Remplace le role applicatif de l'utilisateur. */
    void replaceUserAppRole(String userId, AppRole newRole);

    /** Date de la derniere connexion de chaque utilisateur (id Keycloak -> instant), lue dans les evenements LOGIN. */
    Map<String, Instant> lastLoginByUser();

    /** Cree le compte (active, sans mot de passe) et renvoie son id ; ConflictException si le nom ou l'e-mail existe deja. */
    String createUser(NewKeycloakUser user);

    /** Supprime le compte. */
    void deleteUser(String userId);

    /** Envoie un lien par e-mail pour executer des actions Keycloak (ex. UPDATE_PASSWORD). */
    void sendActionsEmail(String userId, List<String> actions);

    /** Definit le mot de passe (temporary = changement impose a la prochaine connexion) ; BadRequestException si la politique le refuse. */
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

    /** Sessions de l'utilisateur via l'API "compte" de Keycloak, avec son propre jeton. */
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

    /** Serveur e-mail du realm (null si aucun), sans identifiant ni mot de passe. */
    default KeycloakSmtpInfo smtpInfo() {
        return null;
    }
}
