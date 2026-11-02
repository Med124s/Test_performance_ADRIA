package com.loadpilot.backend.service.report;

import com.loadpilot.backend.enums.HttpMethod;
import java.time.Instant;

/** Erreur rencontree pendant une execution (sans en-tete ni secret). */
public record ReportErrorEntry(
        String stepName,
        HttpMethod method,
        String url,
        Integer httpStatus,
        String error,
        Instant timestamp
) {
}
