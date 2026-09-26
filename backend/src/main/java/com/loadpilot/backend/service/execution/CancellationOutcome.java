package com.loadpilot.backend.service.execution;

/**
 * Resultat REEL (jamais suppose) d'une tentative d'annulation, constate sous
 * verrou pessimiste au moment exact de l'ecriture (voir
 * ExecutionTransactionHelper.attemptCancellation) - P0-B.
 */
public enum CancellationOutcome {
    /** L'execution n'avait jamais reellement demarre : transition directe et
     * ecrite ici meme vers CANCELLED. */
    CANCELLED_WHILE_QUEUED,
    /** L'execution est reellement en cours : aucune ecriture ici (voir doc
     * de la methode) - l'appelant doit interrompre le RunningExecutionHandle
     * reel ; le statut final sera ecrit par finalizeExecution. */
    CANCELLATION_REQUESTED_WHILE_RUNNING,
    /** Deja SUCCESS/FAILED/CANCELLED au moment reel de la tentative -
     * annulation impossible, jamais silencieusement ignoree. */
    ALREADY_TERMINAL
}
