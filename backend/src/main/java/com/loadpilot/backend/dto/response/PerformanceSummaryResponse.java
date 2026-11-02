package com.loadpilot.backend.dto.response;

import java.math.BigDecimal;

/** Moyennes des metriques ; null si aucune metrique. averageResponseTime en millisecondes. */
public record PerformanceSummaryResponse(
        BigDecimal averageResponseTime,
        BigDecimal averageThroughput,
        BigDecimal averageErrorRate
) {
}
