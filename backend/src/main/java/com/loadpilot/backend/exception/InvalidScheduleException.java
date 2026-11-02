package com.loadpilot.backend.exception;

/** Configuration de planification invalide (400). */
public class InvalidScheduleException extends RuntimeException {

    public InvalidScheduleException(String message) {
        super(message);
    }
}
