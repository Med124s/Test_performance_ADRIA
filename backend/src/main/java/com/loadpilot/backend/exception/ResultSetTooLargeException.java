package com.loadpilot.backend.exception;

import org.springframework.http.HttpStatus;

/**
 * Lecture refusee AVANT de charger tous les resultats d'une execution (voir ResultLoadGuard) :
 *   - 413 : trop volumineuse pour la memoire de ce serveur, meme seule ;
 *   - 409 : ne tient pas pour l'instant dans la memoire restante (tests en cours, autres lectures),
 *     a reessayer.
 */
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
