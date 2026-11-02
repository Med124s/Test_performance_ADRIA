package com.loadpilot.backend.dto.request;

import com.loadpilot.backend.enums.ExecutionStatus;
import java.time.Instant;
import java.util.UUID;

/** Filtres de l'historique des executions ; tous optionnels et combinables. */
public record ExecutionHistoryFilterRequest(
        ExecutionStatus status,
        UUID scenarioId,
        UUID applicationId,
        Instant dateFrom,
        Instant dateTo,
        String search,
        boolean finishedOnly
) {

    public ExecutionHistoryFilterRequest(ExecutionStatus status, UUID scenarioId, UUID applicationId, Instant dateFrom,
                                         Instant dateTo, String search) {
        this(status, scenarioId, applicationId, dateFrom, dateTo, search, false);
    }
}
