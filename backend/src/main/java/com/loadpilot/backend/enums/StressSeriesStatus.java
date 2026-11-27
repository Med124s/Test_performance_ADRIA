package com.loadpilot.backend.enums;

/** Etat d'une serie d'essais de stress. */
public enum StressSeriesStatus {
    /** Essai en cours, ou pause avant l'essai suivant. */
    RUNNING,
    /** Tous les essais demandes ont abouti. */
    COMPLETED,
    /** Arretee avant la fin : essai non concluant (echoue, annule, LoadPilot sature) ou redemarrage du serveur. */
    INTERRUPTED,
    /** Annulee par un utilisateur. */
    CANCELLED
}
