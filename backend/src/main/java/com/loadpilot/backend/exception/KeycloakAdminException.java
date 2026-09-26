package com.loadpilot.backend.exception;

/** L'appel reel a l'Admin API Keycloak a echoue (reseau, credentials du
 * compte de service invalides, reponse inattendue) - mappee en 502 par
 * GlobalExceptionHandler. Ne contient jamais le secret du client ni un
 * detail technique sensible dans son message. */
public class KeycloakAdminException extends RuntimeException {

    public KeycloakAdminException(String message) {
        super(message);
    }
}
