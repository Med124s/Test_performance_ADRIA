package com.loadpilot.backend.enums;

/**
 * Contrairement a ScenarioStatus/StepStatus (cycle de vie/configuration),
 * ExecutionStatus est bien un RESULTAT D'EXECUTION reel - jamais invente,
 * toujours derive du deroulement effectif des vraies requetes HTTP (voir
 * service.execution.HttpClientExecutionEngine).
 *
 * QUEUED (P0, execution asynchrone) : l'Execution existe reellement en
 * base, mais le moteur n'a pas encore commence a envoyer de requetes -
 * jamais un etat decoratif, une Execution reste reellement dans cet etat
 * le temps que la tache asynchrone soit planifiee (voir
 * ExecutionServiceImpl).
 *
 * PAUSED : execution demarree, mise en pause par un utilisateur - les
 * utilisateurs virtuels n'envoient plus aucune nouvelle requete jusqu'a la
 * reprise (retour a RUNNING) ou l'annulation. Le temps passe en pause n'est
 * compte ni dans la duree de travail du test ni dans le debit.
 *
 * THRESHOLDS_EXCEEDED : le test est alle au bout, mais un seuil de reussite du scenario (taux
 * d'erreurs maximal, P95 maximal) est depasse. FAILED est reserve a un test qui n'a pas pu se
 * derouler (authentification refusee, application injoignable, erreur interne). Les executions
 * anterieures aux seuils gardent leur statut d'origine.
 */
public enum ExecutionStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    SUCCESS,
    THRESHOLDS_EXCEEDED,
    FAILED,
    CANCELLED;

    /** Execution en cours (demarree ou non, en pause ou non) : ni terminee ni annulee. */
    public boolean isActive() {
        return this == QUEUED || this == RUNNING || this == PAUSED;
    }
}
