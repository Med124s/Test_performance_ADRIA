package com.loadpilot.backend.enums;

/**
 * Ou lire une valeur capturee dans la reponse (capture d'une etape, jeton de la Connexion automatique).
 * null equivaut a JSON (comportement historique).
 *
 *   - JSON   : le chemin est un chemin JSON dans le corps (voir JsonPathExtractor).
 *   - HEADER : le chemin est un nom d'en-tete de reponse, ou "Set-Cookie:NOM" pour la valeur du
 *              cookie NOM (voir ResponseHeaderExtractor).
 *   - HTML   : le chemin est un selecteur CSS dans la page HTML, suivi de "::attr(NOM)" pour lire un
 *              attribut (sinon le texte de l'element) - voir HtmlExtractor. Capture d'etape uniquement.
 */
public enum CaptureSource {
    JSON,
    HEADER,
    HTML
}
