package com.loadpilot.backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Parametres du compte de service Keycloak utilise pour administrer les utilisateurs et les roles. */
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
    /** Client vers lequel renvoient les liens envoyes par e-mail (reinitialisation, invitation). */
    private String actionsClientId = "loadpilot-frontend";
    /** Adresse ouverte apres une action envoyee par e-mail. */
    private String actionsRedirectUri = "http://localhost:3000/";
    /** Duree de validite du lien envoye par e-mail, en secondes. */
    private int actionsLifespanSeconds = 43200;
    /** Nombre maximal d'evenements LOGIN lus pour calculer les dernieres connexions. */
    private int loginEventsMax = 2000;
}
