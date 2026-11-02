package com.loadpilot.backend.service.execution;

import java.time.Instant;
import java.util.UUID;

/** Resultat de la reservation d'un declenchement de planification ; claimed = false si elle n'est plus a declencher. */
public record TriggerClaim(
        boolean claimed,
        UUID scheduleId,
        UUID scenarioId,
        UUID createdByAppUserId,
        String scheduleName,
        Instant plannedAt,
        int missedCount
) {

    public static TriggerClaim skipped() {
        return new TriggerClaim(false, null, null, null, null, null, 0);
    }

    public static TriggerClaim claimed(UUID scheduleId, UUID scenarioId, UUID createdByAppUserId, String scheduleName) {
        return claimed(scheduleId, scenarioId, createdByAppUserId, scheduleName, Instant.now());
    }

    public static TriggerClaim claimed(UUID scheduleId, UUID scenarioId, UUID createdByAppUserId, String scheduleName,
                                       Instant plannedAt) {
        return new TriggerClaim(true, scheduleId, scenarioId, createdByAppUserId, scheduleName, plannedAt, 0);
    }

    public static TriggerClaim missed(UUID scheduleId, UUID createdByAppUserId, String scheduleName, Instant firstPlannedAt,
                                      int missedCount) {
        return new TriggerClaim(false, scheduleId, null, createdByAppUserId, scheduleName, firstPlannedAt, missedCount);
    }
}
