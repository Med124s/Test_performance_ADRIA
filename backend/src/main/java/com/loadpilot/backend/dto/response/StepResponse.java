package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.HttpMethod;
import com.loadpilot.backend.enums.StepStatus;
import java.time.Instant;

/** Etape d'un scenario. */
public record StepResponse(
        String id,
        String scenarioId,
        String scenarioName,
        String name,
        HttpMethod method,
        String url,
        String headers,
        String body,
        Integer order,
        Integer expectedStatus,
        Integer thinkTimeMs,
        Integer timeoutSeconds,
        Boolean followRedirects,
        String assertionBodyContains,
        String captureVariableName,
        String captureJsonPath,
        /** null = JSON. */
        com.loadpilot.backend.enums.CaptureSource captureSource,
        String pathParams,
        String queryParams,
        StepStatus status,
        Instant createdAt,
        Instant updatedAt,
        /** Temps de reponse maximal (ms) ; null = aucun controle. */
        Integer maxResponseTimeMs,
        /** Assertion JSON : champ, comparaison (EQUALS / CONTAINS), valeur attendue ; null = aucune. */
        String assertionJsonPath,
        com.loadpilot.backend.enums.JsonAssertionOperator assertionJsonOperator,
        String assertionJsonValue
) {
}
