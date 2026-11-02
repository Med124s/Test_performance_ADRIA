package com.loadpilot.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Test du monitoring avec la configuration saisie ; applicationId permet de reutiliser le secret enregistre. */
public record MonitoringTestRequest(
        UUID applicationId,
        @NotNull(message = "La configuration de monitoring est obligatoire.")
        @Valid
        ApplicationMonitoringRequest monitoring,
        /** Reseau saisi (optionnel) : sinon celui de l'application enregistree (applicationId), sinon le proxy global. */
        @Valid
        ApplicationNetworkRequest network
) {
    public MonitoringTestRequest(UUID applicationId, ApplicationMonitoringRequest monitoring) {
        this(applicationId, monitoring, null);
    }
}
