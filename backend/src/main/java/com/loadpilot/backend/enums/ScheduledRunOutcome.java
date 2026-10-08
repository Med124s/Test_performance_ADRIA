package com.loadpilot.backend.enums;

/** Lancement prevu mais non lance (voir ScheduledRunEvent). */
public enum ScheduledRunOutcome {
    /** Une execution du meme scenario etait encore en cours. */
    SKIPPED_RUNNING,
    /** Limite globale de capacite atteinte. */
    SKIPPED_CAPACITY,
    /** LoadPilot etait arrete a l'heure prevue : jamais rattrape. */
    MISSED,
    /** Lancement refuse pour une autre raison (scenario sans etape, archive...). */
    FAILED
}
