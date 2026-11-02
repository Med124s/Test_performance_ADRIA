package com.loadpilot.backend.service.report;

import java.math.BigDecimal;

/** Statistiques d'une execution ; temps en millisecondes. */
public record ExecutionStatistics(
        int totalRequests,
        int successfulRequests,
        int failedRequests,
        BigDecimal successRate,
        BigDecimal errorRate,
        Long minResponseTime,
        Long maxResponseTime,
        Long avgResponseTime,
        Long p50,
        Long p75,
        Long p90,
        Long p95,
        Long p99,
        BigDecimal throughput
) {
}
