package com.loadpilot.backend.enums;

/**
 * Mode d'authentification applique automatiquement a TOUTES les requetes
 * envoyees vers une Application cible (test de disponibilite et Executions).
 *
 * NONE         : aucune authentification (comportement historique).
 * BEARER_TOKEN : jeton fixe envoye dans "Authorization: Bearer ...".
 * API_KEY      : cle envoyee dans un en-tete ou un parametre de requete.
 * BASIC        : "Authorization: Basic base64(utilisateur:mot de passe)".
 * LOGIN_TOKEN  : LoadPilot appelle d'abord un endpoint de connexion, extrait
 *                le jeton de la reponse JSON puis l'envoie dans un en-tete
 *                (une connexion par Execution, jeton partage par ses VUs).
 */
public enum ApplicationAuthType {
    NONE,
    BEARER_TOKEN,
    API_KEY,
    BASIC,
    LOGIN_TOKEN
}
