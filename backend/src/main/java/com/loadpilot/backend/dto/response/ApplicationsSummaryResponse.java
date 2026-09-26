package com.loadpilot.backend.dto.response;

/**
 * "totalApplications" compte TOUTES les Applications, y compris celles sans
 * statut (jamais testees, voir ApplicationStatus - nullable). Les 3 autres
 * compteurs se basent strictement sur les 3 valeurs reelles de
 * ApplicationStatus (CONNECTED/FAILED/ERROR) : leur somme peut donc etre
 * inferieure a totalApplications.
 */
public record ApplicationsSummaryResponse(
        long totalApplications,
        long connectedApplications,
        long failedApplications,
        long errorApplications
) {
}
