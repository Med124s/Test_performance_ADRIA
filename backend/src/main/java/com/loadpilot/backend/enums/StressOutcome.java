package com.loadpilot.backend.enums;

/** Resultat d'un test de stress termine. */
public enum StressOutcome {
    /** Point de rupture detecte : « Seuils dépassés ». */
    BREAKPOINT,
    /** Aucune rupture avant une limite de LoadPilot : « Réussie ». */
    NO_BREAKPOINT,
    /** LoadPilot sature avant la cible : « Annulée », non concluant. */
    INCONCLUSIVE,
    /** Baseline sans assez de reponses : « Échouée ». */
    BASELINE_UNUSABLE
}
