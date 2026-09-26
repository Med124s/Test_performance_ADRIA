package com.loadpilot.backend.enums;

/**
 * Resultat du dernier test de disponibilite reel d'une Application (voir
 * ApplicationService.testAvailability) - jamais une valeur devinee/simulee.
 *
 * Une Application fraichement creee n'a AUCUN statut (colonne nullable,
 * valeur null cote API) tant qu'aucun test n'a ete lance : lui attribuer une
 * valeur par defaut (ex. un statut "en attente") reviendrait a affirmer un
 * resultat de test qui n'a jamais eu lieu.
 */
public enum ApplicationStatus {

    /** Le test a obtenu une reponse HTTP de succes (code &lt; 400). */
    CONNECTED,

    /** Le test a obtenu une reponse HTTP, mais en erreur (code >= 400). */
    FAILED,

    /** Le test n'a obtenu aucune reponse HTTP (reseau, timeout, hote injoignable...). */
    ERROR
}
