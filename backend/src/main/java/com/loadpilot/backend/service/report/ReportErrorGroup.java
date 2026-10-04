package com.loadpilot.backend.service.report;

import com.loadpilot.backend.enums.HttpMethod;
import java.time.Instant;

/**
 * Erreurs regroupees par code HTTP + etape + message (tableau « Erreurs » du rapport) : nombre
 * d'occurrences et heure de la premiere apparition. Aucun secret (memes champs que ReportErrorEntry).
 */
public record ReportErrorGroup(
        Integer httpStatus,
        String stepName,
        HttpMethod method,
        String error,
        int count,
        Instant firstAt
) {
}
