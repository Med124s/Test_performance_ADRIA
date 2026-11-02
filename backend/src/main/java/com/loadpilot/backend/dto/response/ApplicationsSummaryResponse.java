package com.loadpilot.backend.dto.response;

/** Compteurs d'applications par statut ; totalApplications compte aussi celles jamais testees. */
public record ApplicationsSummaryResponse(
        long totalApplications,
        long connectedApplications,
        long failedApplications,
        long errorApplications
) {
}
