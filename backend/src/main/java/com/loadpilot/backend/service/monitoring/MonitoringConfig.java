package com.loadpilot.backend.service.monitoring;

import com.loadpilot.backend.enums.MonitoringAuthType;
import com.loadpilot.backend.enums.MonitoringType;
import java.util.Map;

/** Configuration effective du monitoring d'une application (secret compris : jamais journalisee ni exposee). */
public record MonitoringConfig(MonitoringType type, String url, MonitoringAuthType authType, String headerName, String secret,
        int intervalSeconds) {

    public boolean enabled() {
        return type != null && type != MonitoringType.NONE && url != null && !url.isBlank();
    }

    /** En-tetes d'authentification a envoyer a l'endpoint de monitoring. */
    public Map<String, String> authHeaders() {
        if (authType == null || secret == null || secret.isBlank()) return Map.of();
        return switch (authType) {
            case BEARER -> Map.of("Authorization", "Bearer " + secret);
            case HEADER -> headerName == null || headerName.isBlank() ? Map.of() : Map.of(headerName.trim(), secret);
            case NONE -> Map.of();
        };
    }

    @Override
    public String toString() {
        // Jamais le secret, meme dans un log ou un message d'exception.
        return "MonitoringConfig[type=" + type + ", url=" + url + ", authType=" + authType + ", intervalSeconds=" + intervalSeconds + "]";
    }
}
