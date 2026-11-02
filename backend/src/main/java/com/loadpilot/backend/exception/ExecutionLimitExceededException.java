package com.loadpilot.backend.exception;

/** Nouvelle execution refusee : limite de capacite du serveur atteinte. */
public class ExecutionLimitExceededException extends RuntimeException {

    public ExecutionLimitExceededException(String message) {
        super(message);
    }
}
