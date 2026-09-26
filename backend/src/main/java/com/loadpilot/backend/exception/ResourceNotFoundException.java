package com.loadpilot.backend.exception;

/** Ressource introuvable (ex: Application par id) - mappee en 404 par GlobalExceptionHandler. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
