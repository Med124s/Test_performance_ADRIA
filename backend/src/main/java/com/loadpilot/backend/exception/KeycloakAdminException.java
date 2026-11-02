package com.loadpilot.backend.exception;

/** Echec d'un appel a l'Admin API Keycloak (502). */
public class KeycloakAdminException extends RuntimeException {

    public KeycloakAdminException(String message) {
        super(message);
    }
}
