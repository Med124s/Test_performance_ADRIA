package com.loadpilot.backend.enums;

/**
 * Action auditee (voir entity.AuditLog) - liste volontairement restreinte
 * aux actions reellement significatives du produit (Phase 12), pas une
 * enumeration exhaustive de tous les verbes HTTP possibles.
 */
public enum AuditAction {
    CREATE,
    READ,
    UPDATE,
    DELETE,
    TEST,
    EXECUTE,
    RETRY,
    CANCEL,
    LOGIN,
    LOGOUT,
    // P1-B - cycle de vie d'une ScheduledExecution (module SCHEDULING) :
    // CREATE/UPDATE/DELETE ci-dessus restent reutilises pour le CRUD
    // classique d'une planification ; ces trois actions couvrent ce qui
    // n'a pas d'equivalent generique.
    ENABLE,
    DISABLE,
    TRIGGER,
    // P1-C - traçabilité explicitement demandée de la consultation de
    // l'historique et de l'export de rapports (EXECUTION module) - seule
    // exception delibérée à la regle "jamais auditer une simple lecture"
    // suivie jusqu'ici (LOGIN/LOGOUT mis a part) : ici, le prompt P1-C
    // l'exige explicitement (tracabilite d'acces a des rapports de
    // performance, un besoin de conformite reel dans un contexte
    // entreprise) - voir ExecutionController#history/getReport/exportReport
    // et le rapport P1-C, section "Limitations" pour la discussion du
    // volume d'AuditLog que cela peut generer.
    VIEW_HISTORY,
    EXPORT_REPORT,
    EXPORT_CSV
}
