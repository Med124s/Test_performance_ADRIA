package com.loadpilot.backend.service.monitoring;

/** Lecture du monitoring impossible : message lisible destine a l'utilisateur (jamais de secret). */
public class MonitoringException extends Exception {
    public MonitoringException(String message) {
        super(message);
    }
}
