package com.loadpilot.backend.enums;

/**
 * Statut de CYCLE DE VIE d'un scenario - PAS un resultat d'execution.
 *
 * DRAFT    : brouillon de l'assistant « Nouveau scénario » (enregistre en cours de saisie, ou
 *            cree automatiquement par l'etape « Tester »). Ni executable ni planifiable ;
 *            supprimable par SUPER_ADMIN et PERFORMANCE_ENGINEER. Devient ACTIVE a la
 *            publication (POST /api/scenarios/{id}/publish, apres validation complete).
 * ACTIVE   : scenario termine, executable et planifiable.
 * INACTIVE : scenario desactive volontairement (POST /api/scenarios/{id}/deactivate) :
 *            modifiable et consultable, mais non executable ; ses planifications actives
 *            sont suspendues (ScheduledExecution.pausedByScenario) et peuvent etre
 *            reactivees apres POST /api/scenarios/{id}/activate.
 */
public enum ScenarioStatus {
    DRAFT,
    ACTIVE,
    INACTIVE
}
