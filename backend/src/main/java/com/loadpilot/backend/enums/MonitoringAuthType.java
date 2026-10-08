package com.loadpilot.backend.enums;

/** Authentification de l'endpoint de monitoring : aucune, jeton (Authorization: Bearer) ou en-tete libre. */
public enum MonitoringAuthType {
    NONE,
    BEARER,
    HEADER
}
