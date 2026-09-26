package com.loadpilot.backend.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolution de l'IP "reelle" d'une requete (voir Phase 12, section 17) -
 * volontairement minimaliste, aucune dependance supplementaire.
 *
 * Renvoie {@link HttpServletRequest#getRemoteAddr()} : l'adresse du PAIR TCP
 * DIRECT de la connexion. En local (sans reverse proxy), c'est bien l'IP
 * reelle du client (ex: "127.0.0.1" ou "0:0:0:0:0:0:0:1" en IPv6).
 *
 * Deliberement PAS de lecture de "X-Forwarded-For" ici : ce header est
 * entierement controle par le client final et donc usurpable a volonte tant
 * qu'aucun reverse proxy de confiance ne le reecrit - le lire aveuglement
 * permettrait a n'importe qui d'inscrire une IP arbitraire dans l'audit. Si
 * ce backend est un jour deploye derriere un reverse proxy de confiance, la
 * bonne approche est d'activer explicitement le support Spring
 * (ForwardedHeaderFilter) cote configuration serveur - pas de le
 * reimplementer partiellement ici.
 */
public final class RequestIpResolver {

    private RequestIpResolver() {
    }

    public static String resolve(HttpServletRequest request) {
        return request != null ? request.getRemoteAddr() : null;
    }
}
