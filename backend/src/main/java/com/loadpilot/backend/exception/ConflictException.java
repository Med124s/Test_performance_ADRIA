package com.loadpilot.backend.exception;

/** Conflit metier (409). */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
