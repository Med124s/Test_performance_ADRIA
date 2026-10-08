package com.loadpilot.backend.enums;

/**
 * Action auditee (voir entity.AuditLog) - liste volontairement restreinte
 * aux actions reellement significatives du produit (Phase 12), pas une
 * enumeration exhaustive de tous les verbes HTTP possibles. Libelles
 * francais : voir service.audit.AuditLabels.
 */
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
    // P1-B - cycle de vie d'une ScheduledExecution (module SCHEDULING).
    ENABLE,
    DISABLE,
    TRIGGER,
    // Consultations : journalisees seulement si le reglage « Journaliser aussi les
    // consultations » est active (desactive par defaut, voir AppSettingsService).
    VIEW_HISTORY,
    VIEW_REPORT,
    /** Ancien code utilise a tort pour les CONSULTATIONS de rapport (lignes historiques) ; les vrais exports sont EXPORT_CSV. */
    EXPORT_REPORT,
    EXPORT_CSV,
    // Suppression coherente (migration 017) : archivage logique et restauration.
    ARCHIVE,
    RESTORE,
    // Pause / reprise d'une execution en cours (module EXECUTION).
    PAUSE,
    RESUME,
    // Securite (module SECURITY) : evenements du systeme d'authentification et
    // actions d'administration des comptes. Jamais de mot de passe enregistre.
    LOGIN_FAILED,
    ACCOUNT_LOCKED,
    PASSWORD_SET,
    PASSWORD_RESET_LINK,
    PASSWORD_CHANGED,
    ROLE_CHANGE,
    /** Purge automatique des entrees plus anciennes que la duree de conservation. */
    PURGE
}
