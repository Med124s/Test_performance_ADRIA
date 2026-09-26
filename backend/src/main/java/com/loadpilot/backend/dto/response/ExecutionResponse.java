package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.ExecutionStatus;
import java.time.Instant;

public record ExecutionResponse(
        String id,
        String scenarioId,
        String scenarioName,
        Instant startedAt,
        Instant finishedAt,
        ExecutionStatus status,
        Integer virtualUsers,
        Integer rampUpSeconds,
        Integer durationSeconds,
        Integer iterations,
        Integer totalSteps,
        Integer successfulSteps,
        Integer failedSteps,
        Long duration,
        String errorMessage
) {
}
