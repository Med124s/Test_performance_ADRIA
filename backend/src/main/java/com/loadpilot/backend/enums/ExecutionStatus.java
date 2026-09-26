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
 * ExecutionServiceImpl). PAUSED n'existe PAS : pause/reprise n'est pas
 * implementee, jamais affichee comme si elle l'etait.
 */
public enum ExecutionStatus {
    QUEUED,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED
}
