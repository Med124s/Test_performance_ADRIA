package com.loadpilot.backend.dto.response;

import java.time.Instant;
import java.util.List;

/** Prochains lancements calcules exactement comme le declencheur ; "cronExpression" = expression normalisee (6 champs). */
public record SchedulePreviewResponse(List<Instant> nextRuns, String cronExpression) {
}