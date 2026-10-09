package com.loadpilot.backend.dto.response;

/** Durees de conservation (jours) : resultats detailles et en-tetes / corps des requetes. */
public record ResultsRetentionSettingsResponse(int resultsDays, int requestDetailsDays) {
}
