package com.loadpilot.backend.service.monitoring;

import com.loadpilot.backend.enums.MonitoringAuthType;
import com.loadpilot.backend.enums.MonitoringType;
import com.loadpilot.backend.service.http.NetworkProfile;
import java.util.Map;

/**
 * Configuration effective du monitoring d'une application (secret compris : jamais journalisee ni
 * exposee). "network" : proxy pour joindre l'endpoint de monitoring (celui de l'application, ou en
 * direct si « Monitoring sans proxy ») ; null = proxy global.
 */
public record MonitoringConfig(MonitoringType type, String url, MonitoringAuthType authType, String headerName, String secret,
        int intervalSeconds, NetworkProfile network) {

    public MonitoringConfig {
        if (network == null) network = NetworkProfile.inherit();
    }

    /** Compatibilite : proxy global. */
    public MonitoringConfig(MonitoringType type, String url, MonitoringAuthType authType, String headerName, String secret,
            int intervalSeconds) {
        this(type, url, authType, headerName, secret, intervalSeconds, NetworkProfile.inherit());
    }

    public MonitoringConfig withNetwork(NetworkProfile profile) {
        return new MonitoringConfig(type, url, authType, headerName, secret, intervalSeconds, profile);
    }

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
        return "MonitoringConfig[type=" + type + ", url=" + url + ", authType=" + authType + ", intervalSeconds=" + intervalSeconds
                + ", " + network + "]";
    }
}
