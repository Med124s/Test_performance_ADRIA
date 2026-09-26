package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.ApplicationStatus;

/**
 * Reponse de POST /api/applications/{id}/test - reflete le vrai resultat de
 * la requete HTTP reelle effectuee vers Application.url (voir
 * service.http.HttpAvailabilityChecker), jamais une valeur simulee.
 */
public record ApplicationTestResponse(
        ApplicationStatus status,
        Integer httpStatus,
        long responseTimeMs,
        String message
) {
}
