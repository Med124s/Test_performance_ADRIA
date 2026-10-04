package com.loadpilot.backend.service.execution;

/** Echec d'authentification aupres de l'Application cible. Le message est
 * destine a l'utilisateur, sans prefixe (l'appelant ajoute
 * "Authentification : ") et ne contient jamais de secret ni le corps de la
 * reponse de login. */
public class ApplicationAuthenticationException extends RuntimeException {

    public enum Kind {
        /** L'application a repondu mais refuse la connexion (4xx/5xx, voir httpStatus). */
        REJECTED,
        /** Connexion acceptee mais aucun jeton au chemin configure. */
        TOKEN_NOT_FOUND,
        /** Reponse de login inexploitable (non JSON). */
        INVALID_RESPONSE,
        /** Endpoint de login injoignable (reseau, DNS, timeout). */
        UNREACHABLE
    }

    private final Kind kind;
    private final Integer httpStatus;

    public ApplicationAuthenticationException(Kind kind, String message) {
        this(kind, message, null);
    }

    public ApplicationAuthenticationException(Kind kind, String message, Integer httpStatus) {
        super(message);
        this.kind = kind;
        this.httpStatus = httpStatus;
    }

    public Kind kind() {
        return kind;
    }

    /** Code HTTP de la reponse de login (REJECTED), sinon null. */
    public Integer httpStatus() {
        return httpStatus;
    }
}
