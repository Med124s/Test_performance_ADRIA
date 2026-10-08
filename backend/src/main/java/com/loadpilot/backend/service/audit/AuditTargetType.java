package com.loadpilot.backend.service.audit;

/** Type d'objet concerne par une entree d'audit (pour un nom lisible et un lien vers l'objet). */
public enum AuditTargetType {
    APPLICATION,
    SCENARIO,
    EXECUTION,
    SCHEDULE,
    USER,
    SETTINGS
}
