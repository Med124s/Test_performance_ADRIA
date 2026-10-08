package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.enums.ScheduledRunOutcome;
import java.time.Instant;
import java.util.List;

/**
 * Historique d'une planification : executions lancees (kind = EXECUTION) et lancements non faits
 * (kind = EVENT : ignore, manque, refuse, avec la raison), du plus recent au plus ancien.
 */
public record ScheduleHistoryResponse(String scheduleId, List<Item> items, boolean truncated) {

    public record Item(
            String kind,
            Instant at,
            String executionId,
            ExecutionStatus status,
            Long p95Ms,
            Double errorRatePercent,
            Double throughputRps,
            Long totalRequests,
            Long durationMs,
            ScheduledRunOutcome outcome,
            String reason
    ) {
    }
}