package com.loadpilot.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * « Tester le monitoring » avec la configuration saisie (pas forcement enregistree). applicationId
 * (optionnel) : application existante dont le secret enregistre est reutilise si le champ est vide.
 */
public record MonitoringTestRequest(
        UUID applicationId,
        @NotNull(message = "La configuration de monitoring est obligatoire.")
        @Valid
        ApplicationMonitoringRequest monitoring,
        /** Reseau saisi (optionnel) : sinon celui de l'application enregistree (applicationId), sinon le proxy global. */
        @Valid
        ApplicationNetworkRequest network
) {
    /** Compatibilite : sans reglage reseau saisi. */
    public MonitoringTestRequest(UUID applicationId, ApplicationMonitoringRequest monitoring) {
        this(applicationId, monitoring, null);
    }
}
