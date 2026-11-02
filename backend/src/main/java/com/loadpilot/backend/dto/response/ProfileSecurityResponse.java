package com.loadpilot.backend.dto.response;

import java.time.Instant;
import java.util.List;

/** Derniere connexion et sessions ouvertes de l'utilisateur connecte. */
public record ProfileSecurityResponse(Instant lastLoginAt, String lastLoginIp, List<Session> sessions, boolean devicesKnown,
                                      String message) {

    /** device : « Chrome sur Windows » (null si inconnu) ; ip : « localhost » pour la machine elle-meme. */
    public record Session(String id, String device, boolean mobile, String ip, Instant started, Instant lastAccess, boolean current) {
    }
}
