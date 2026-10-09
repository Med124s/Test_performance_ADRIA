package com.loadpilot.backend.service.keycloak;

import java.time.Instant;

/** Connexion reussie (evenement LOGIN) : date et adresse IP. */
public record KeycloakLogin(Instant time, String ipAddress) {
}
