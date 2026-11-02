package com.loadpilot.backend.exception;

import org.springframework.http.HttpStatus;

/** Resultats d'une execution trop volumineux pour etre charges : 413 (trop gros pour ce serveur) ou 409 (memoire insuffisante pour l'instant). */
public class ResultSetTooLargeException extends RuntimeException {

    private final HttpStatus status;

    public ResultSetTooLargeException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
