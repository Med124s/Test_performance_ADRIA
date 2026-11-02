package com.loadpilot.backend.enums;

/** Authentification appliquee a toutes les requetes envoyees vers une application testee. */
public enum ApplicationAuthType {
    NONE,
    BEARER_TOKEN,
    API_KEY,
    BASIC,
    LOGIN_TOKEN
}
