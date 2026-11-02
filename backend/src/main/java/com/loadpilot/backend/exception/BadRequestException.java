package com.loadpilot.backend.exception;

/** Requete refusee (400) pour une regle metier. */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
