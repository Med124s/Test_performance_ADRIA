package com.loadpilot.backend.service.keycloak;

import java.time.Instant;
import java.util.List;

/** Session ouverte d'un utilisateur ; browser / os / device fournis par l'API "compte" ; current = session de la demande. */
public record KeycloakSession(String id, String ipAddress, Instant started, Instant lastAccess, List<String> clients,
                              String browser, String os, String device, boolean mobile, boolean current) {
}
