package com.loadpilot.backend.service.keycloak;

import java.time.Instant;
import java.util.List;

/**
 * Session ouverte d'un utilisateur. browser / os / device : renseignes par l'API « compte » de
 * Keycloak (null via l'API d'administration, qui ne les connait pas). current : la session du
 * jeton utilise pour la demande.
 */
public record KeycloakSession(String id, String ipAddress, Instant started, Instant lastAccess, List<String> clients,
                              String browser, String os, String device, boolean mobile, boolean current) {
}
