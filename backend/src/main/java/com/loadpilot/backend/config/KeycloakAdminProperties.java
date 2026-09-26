package com.loadpilot.backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Phase 25 - configuration du compte de service Keycloak dedie a
 * l'administration des utilisateurs/roles (jamais le compte admin Keycloak
 * lui-meme - voir service.keycloak.HttpKeycloakAdminClient). Le client
 * "loadpilot-backend-admin" (confidentiel, serviceAccountsEnabled=true,
 * roles realm-management view-users/manage-users/query-users uniquement)
 * est provisionne separement dans Keycloak, hors de ce depot.
 *
 * "client-secret" n'a volontairement AUCUNE valeur par defaut (chaine vide)
 * - jamais un secret devine/trivial en dur, meme pour le developpement (voir
 * la meme politique deja appliquee a DB_PASSWORD, application-dev.yml).
 */
@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "app.keycloak.admin")
public class KeycloakAdminProperties {

    private String serverUrl = "http://localhost:8081";
    private String realm = "loadpilot";
    private String clientId = "loadpilot-backend-admin";
    private String clientSecret = "";
    private long timeoutSeconds = 5;
}
