package com.loadpilot.backend.service.report;

import com.loadpilot.backend.enums.HttpMethod;
import java.math.BigDecimal;

/** Statistiques d'une etape sur toutes ses requetes d'une execution. */
public record StepReportEntry(
        String stepId,
        String stepName,
        HttpMethod method,
        String url,
        int total,
        int success,
        int failed,
        Long avgResponseTime,
        Long minResponseTime,
        Long maxResponseTime,
        Long p95,
        Long p99,
        BigDecimal errorRate
) {
}
