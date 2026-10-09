package com.loadpilot.backend.service.keycloak;

/** Serveur e-mail du systeme d'authentification, SANS identifiant ni mot de passe. */
public record KeycloakSmtpInfo(String host, String port, String from, String fromName, boolean authenticated) {
}
