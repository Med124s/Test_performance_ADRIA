package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.HttpMethod;
import java.time.Instant;

/** Une requete d'une execution ; "detailKind" = FAILURE ou SAMPLE si le detail complet est conserve. */
public record ExecutionRequestRowResponse(
        String resultId,
        String stepId,
        String stepName,
        HttpMethod method,
        Integer virtualUser,
        Integer iteration,
        Instant timestamp,
        Integer httpStatus,
        Long responseTime,
        Boolean success,
        String error,
        String requestUrl,
        String detailKind
) {
}
