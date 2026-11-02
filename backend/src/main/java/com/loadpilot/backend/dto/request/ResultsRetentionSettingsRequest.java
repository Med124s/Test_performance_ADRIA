package com.loadpilot.backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Durees de conservation (jours) : resultats detailles, et en-tetes / corps des requetes (au plus resultsDays). */
public record ResultsRetentionSettingsRequest(
        @NotNull @Min(value = 7, message = "Durée des résultats : au moins 7 jours.")
        @Max(value = 3650, message = "Durée des résultats : au plus 3650 jours.") Integer resultsDays,
        @NotNull @Min(value = 1, message = "Durée du détail des requêtes : au moins 1 jour.")
        @Max(value = 3650, message = "Durée du détail des requêtes : au plus 3650 jours.") Integer requestDetailsDays
) {
}
