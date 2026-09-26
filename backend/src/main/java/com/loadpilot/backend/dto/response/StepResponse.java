package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.HttpMethod;
import com.loadpilot.backend.enums.StepStatus;
import java.time.Instant;

/**
 * "scenarioName" est expose en plus de "scenarioId" par confort d'affichage
 * - jamais l'entite Scenario complete. Aucun createdBy (Step n'en a pas,
 * voir entity.Step).
 */
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
        StepStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}
