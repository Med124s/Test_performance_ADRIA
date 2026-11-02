package com.loadpilot.backend.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/** Metrique ; applicationId et scenarioId peuvent etre null. */
public record MetricResponse(
        String id,
        String applicationId,
        String scenarioId,
        String stepId,
        String executionId,
        Integer responseTime,
        Integer statusCode,
        BigDecimal throughput,
        BigDecimal errorRate,
        Instant timestamp
) {
}
