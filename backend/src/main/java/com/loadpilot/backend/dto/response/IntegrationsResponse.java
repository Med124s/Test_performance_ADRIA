package com.loadpilot.backend.dto.response;

import java.util.List;

/**
 * Parametres > Integrations (Super administrateur) - AUCUN secret : ni mot de passe SMTP, ni
 * identifiant, ni secret de monitoring ; les adresses de monitoring sont montrees sans
 * identifiants ni parametres.
 */
public record IntegrationsResponse(Mail mail, AuthMail authenticationMail, List<Monitoring> monitoring) {

    /** Serveur e-mail de LoadPilot (notifications, e-mail de test). kind : MAILPIT ou SMTP. */
    public record Mail(boolean enabled, String kind, String host, int port, String from, String fromName, boolean authenticated,
                       boolean reachable, String message) {
    }

    /** Serveur e-mail du systeme d'authentification (mot de passe oublie...) ; null si non configure ou illisible. */
    public record AuthMail(String host, String port, String from, String fromName, boolean authenticated) {
    }

    /** Monitoring CPU / RAM d'une application : type (PROMETHEUS, ACTUATOR), adresse sans secret, etat. */
    public record Monitoring(String applicationId, String applicationName, boolean archived, String type, String endpoint,
                             boolean reachable, String message) {
    }
}
