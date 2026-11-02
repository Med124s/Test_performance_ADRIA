package com.loadpilot.backend.enums;

/** Module fonctionnel d'une entree d'audit. */
public enum AuditModule {
    AUTH,
    USER,
    APPLICATION,
    SCENARIO,
    STEP,
    EXECUTION,
    METRIC,
    DASHBOARD,
    ADMIN,
    SCHEDULING,
    /** Connexions, blocages, mots de passe, roles, activation des comptes. */
    SECURITY,
    /** Reglages de LoadPilot (Parametres). */
    SETTINGS
}
