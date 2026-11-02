package com.loadpilot.backend.service.execution;

/** Resultat d'une tentative d'annulation, constate sous verrou. */
public enum CancellationOutcome {
    /** Execution pas encore demarree : passee directement a CANCELLED. */
    CANCELLED_WHILE_QUEUED,
    /** Execution en cours : l'appelant interrompt l'execution, le statut final est ecrit a la finalisation. */
    CANCELLATION_REQUESTED_WHILE_RUNNING,
    /** Execution deja terminee. */
    ALREADY_TERMINAL
}
