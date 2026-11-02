package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.enums.ScheduleNotifyMode;
import com.loadpilot.backend.enums.ScheduleState;
import com.loadpilot.backend.enums.ScheduleType;
import com.loadpilot.backend.enums.ScheduledRunOutcome;
import java.time.Instant;

/** Planification : configuration, etat, derniere execution lancee et dernier lancement non fait. */
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
        String lastTriggerError,
        Instant endAt,
        ScheduleNotifyMode notifyMode,
        ScheduleState state,
        String stateReason,
        LastRun lastRun,
        LastEvent lastEvent
) {

    /** Derniere execution lancee par la planification ; "p95ChangePercent" = ecart avec l'execution precedente. */
    public record LastRun(String executionId, ExecutionStatus status, Instant startedAt, Long p95Ms,
                          Long previousP95Ms, Double p95ChangePercent, Double errorRatePercent) {
    }

    /** Dernier lancement prevu mais non fait (ignore, manque, refuse). */
    public record LastEvent(ScheduledRunOutcome outcome, Instant plannedAt, String reason) {
    }
}