package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.HttpMethod;
import java.time.Instant;

/**
 * "url" est l'URL REELLEMENT resolue au moment de l'appel (relative ->
 * absolue via Application.url), pas le champ brut de Step - voir
 * mapper.ExecutionStepResultMapper / service.execution.UrlResolver.
 */
public record ExecutionStepResultResponse(
        String stepId,
        String stepName,
        HttpMethod method,
        String url,
        Integer httpStatus,
        Long responseTime,
        Boolean success,
        String error,
        Instant timestamp
) {
}
