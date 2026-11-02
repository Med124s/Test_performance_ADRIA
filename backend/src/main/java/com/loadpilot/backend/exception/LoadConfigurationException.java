package com.loadpilot.backend.exception;

/** Parametre de charge au-dela des limites configurees (400). */
public class LoadConfigurationException extends RuntimeException {

    public LoadConfigurationException(String message) {
        super(message);
    }
}
