package com.loadpilot.backend.exception;

/**
 * Conflit metier controle (ex: suppression d'une Application ayant encore
 * des Scenarios rattaches) - mappee en 409 par GlobalExceptionHandler.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
