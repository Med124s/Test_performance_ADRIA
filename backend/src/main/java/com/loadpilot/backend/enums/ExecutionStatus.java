package com.loadpilot.backend.enums;

/** Statut d'une execution : en attente, en cours, en pause, puis reussie, seuils depasses, echouee ou annulee. */
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
