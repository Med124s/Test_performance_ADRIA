package com.loadpilot.backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Reglages du journal d'audit (Parametres). */
public record AuditSettingsRequest(
        @NotNull(message = "Indiquez si les consultations sont journalisées.")
        Boolean logReads,

        @NotNull(message = "La durée de conservation est obligatoire.")
        @Min(value = 30, message = "La durée de conservation doit être d'au moins 30 jours.")
        @Max(value = 3650, message = "La durée de conservation ne peut pas dépasser 3650 jours (10 ans).")
        Integer retentionDays
) {
}
