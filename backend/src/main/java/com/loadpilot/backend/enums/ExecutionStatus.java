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
 */
public enum ExecutionStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    SUCCESS,
    FAILED,
    CANCELLED;

    /** Execution en cours (demarree ou non, en pause ou non) : ni terminee ni annulee. */
    public boolean isActive() {
        return this == QUEUED || this == RUNNING || this == PAUSED;
    }
}
