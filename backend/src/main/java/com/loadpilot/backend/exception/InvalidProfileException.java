package com.loadpilot.backend.exception;

/** Valeur invalide pour une preference de profil (400). */
public class InvalidProfileException extends RuntimeException {

    public InvalidProfileException(String message) {
        super(message);
    }
}
