package com.loadpilot.backend.dto.request;

import com.loadpilot.backend.enums.MonitoringAuthType;
import com.loadpilot.backend.enums.MonitoringType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Monitoring (optionnel) du processus de l'application testee. Regles propres a chaque type
 * verifiees par MonitoringSupport (400 explicite).
 *
 * SECRET (secret) : comme pour l'authentification, vide ou absent en mise a jour = valeur deja
 * enregistree conservee (l'API ne renvoie jamais le secret).
 */
public record ApplicationMonitoringRequest(
        @NotNull(message = "Le type de monitoring est obligatoire.")
        MonitoringType type,

        @Size(max = 1000, message = "L'URL de monitoring ne doit pas depasser 1000 caracteres.")
        String url,

        MonitoringAuthType authType,

        @Size(max = 255, message = "Le nom d'en-tete ne doit pas depasser 255 caracteres.")
        String headerName,

        @Size(max = 4000, message = "Le jeton ne doit pas depasser 4000 caracteres.")
        String secret,

        @Min(value = 1, message = "L'intervalle de lecture doit etre d'au moins 1 seconde.")
        @Max(value = 60, message = "L'intervalle de lecture ne doit pas depasser 60 secondes.")
        Integer intervalSeconds,

        /** true = lecture en direct, sans proxy (URL de monitoring souvent interne) ; null = inchange (avec le proxy). */
        Boolean withoutProxy
) {
    /** Compatibilite : sans reglage du proxy du monitoring. */
    public ApplicationMonitoringRequest(MonitoringType type, String url, MonitoringAuthType authType, String headerName,
            String secret, Integer intervalSeconds) {
        this(type, url, authType, headerName, secret, intervalSeconds, null);
    }
}
