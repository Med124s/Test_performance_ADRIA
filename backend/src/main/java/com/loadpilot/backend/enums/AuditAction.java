package com.loadpilot.backend.enums;

/** Action enregistree dans le journal d'audit. */
public enum AuditAction {
    CREATE,
    /** Ancienne consultation generique (lignes historiques uniquement). */
    READ,
    UPDATE,
    DELETE,
    TEST,
    EXECUTE,
    /** Fin d'une execution (action automatique, acteur Systeme). */
    FINISH,
    RETRY,
    CANCEL,
    LOGIN,
    LOGOUT,
    /** Cycle de vie d'une planification. */
    ENABLE,
    DISABLE,
    TRIGGER,
    /** Consultations : journalisees seulement si le reglage est active. */
    VIEW_HISTORY,
    VIEW_REPORT,
    /** Ancien code des consultations de rapport (anciennes lignes). */
    EXPORT_REPORT,
    EXPORT_CSV,
    /** Archivage logique et restauration. */
    ARCHIVE,
    RESTORE,
    // Pause / reprise d'une execution en cours (module EXECUTION).
    PAUSE,
    RESUME,
    /** Securite : evenements de connexion et administration des comptes. */
    LOGIN_FAILED,
    ACCOUNT_LOCKED,
    PASSWORD_SET,
    PASSWORD_RESET_LINK,
    PASSWORD_CHANGED,
    ROLE_CHANGE,
    /** Purge automatique des entrees plus anciennes que la duree de conservation. */
    PURGE
}
