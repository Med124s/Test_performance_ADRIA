package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.ApplicationStatus;

/** Resultat du test de connectivite d'une application. */
public record ApplicationTestResponse(
        ApplicationStatus status,
        Integer httpStatus,
        long responseTimeMs,
        String message
) {
}
