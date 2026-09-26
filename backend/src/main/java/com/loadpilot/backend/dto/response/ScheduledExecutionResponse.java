package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.ScheduleType;
import java.time.Instant;

public record ScheduledExecutionResponse(
        String id,
        String scenarioId,
        String scenarioName,
        String applicationId,
        String applicationName,
        String name,
        ScheduleType scheduleType,
        String cronExpression,
        Instant runAt,
        String timezone,
        Instant nextRunAt,
        boolean enabled,
        String createdByUsername,
        Instant createdAt,
        Instant updatedAt,
        Instant lastTriggeredAt,
        String lastExecutionId,
        String lastTriggerError
) {
}
