package com.loadpilot.backend.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * Parametres > Mon profil > Securite : derniere connexion et sessions ouvertes de l'utilisateur
 * connecte. devicesKnown = false : le systeme d'authentification n'a pas donne le navigateur /
 * systeme (sessions lues par l'API d'administration). message : explication si indisponible.
 */
public record ProfileSecurityResponse(Instant lastLoginAt, String lastLoginIp, List<Session> sessions, boolean devicesKnown,
                                      String message) {

    /** device : « Chrome sur Windows » (null si inconnu) ; ip : « localhost » pour la machine elle-meme. */
    public record Session(String id, String device, boolean mobile, String ip, Instant started, Instant lastAccess, boolean current) {
    }
}
