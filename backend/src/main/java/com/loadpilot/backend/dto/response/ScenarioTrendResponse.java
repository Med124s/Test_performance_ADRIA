package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.ExecutionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Evolution des performances d'un scenario sur la periode, de la plus ancienne execution a la plus recente. */
public record ScenarioTrendResponse(String scenarioId, String scenarioName, List<StepOption> steps, String stepName, List<Point> points) {

    public record StepOption(String name, String method) {
    }

    public record Point(String executionId, Instant startedAt, ExecutionStatus status, Long p95Ms, BigDecimal errorRate,
            Double throughputRps, Integer virtualUsers, Long stepP95Ms, BigDecimal stepErrorRate, Integer stepRequests) {
    }
}
