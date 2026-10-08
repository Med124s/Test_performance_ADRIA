package com.loadpilot.backend.enums;

/**
 * Module fonctionnel d'une entree d'audit. Les categories affichees (filtre
 * « Catégorie » de la page Journal d'audit) regroupent ces modules : voir
 * service.audit.AuditCategory.
 */
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
