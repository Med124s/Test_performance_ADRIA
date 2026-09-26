package com.loadpilot.backend.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * applicationId/scenarioId peuvent etre null (voir entity.Metric) ;
 * stepId/executionId sont toujours renseignes.
 */
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
