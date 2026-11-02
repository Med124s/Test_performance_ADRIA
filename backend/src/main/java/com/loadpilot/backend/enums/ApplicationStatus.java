package com.loadpilot.backend.enums;

/** Resultat du dernier test de connectivite d'une application. */
public enum ApplicationStatus {

    /** Le test a obtenu une reponse HTTP de succes (code &lt; 400). */
    CONNECTED,

    /** Le test a obtenu une reponse HTTP, mais en erreur (code >= 400). */
    FAILED,

    /** Le test n'a obtenu aucune reponse HTTP (reseau, timeout, hote injoignable...). */
    ERROR
}
