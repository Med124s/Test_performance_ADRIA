package com.loadpilot.backend.enums;

/**
 * Detail des requetes conserve pour une execution (reglage par scenario, fige au lancement) :
 *   NONE                : aucun detail (seule la ligne de resume de chaque requete existe) ;
 *   FAILURES            : detail des requetes en echec (quotas par type d'erreur et par etape) ;
 *   FAILURES_AND_SAMPLE : echecs + echantillon des requetes reussies (premieres iterations de
 *                         chaque etape pour chaque utilisateur virtuel) - valeur par defaut.
 */
public enum RequestDetailMode {
    NONE,
    FAILURES,
    FAILURES_AND_SAMPLE
}
