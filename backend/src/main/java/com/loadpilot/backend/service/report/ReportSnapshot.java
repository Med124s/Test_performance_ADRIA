package com.loadpilot.backend.service.report;

import com.loadpilot.backend.dto.response.ExecutionExtremesResponse;
import com.loadpilot.backend.dto.response.ExecutionReportResponse;
import com.loadpilot.backend.dto.response.ExecutionTimelineResponse;
import java.time.Instant;
import java.util.List;

/** Rapport fige d'une execution terminee : rapport, analyse dans le temps, cartes Minimum / Maximum et donnees de comparaison. */
public record ReportSnapshot(
        int version,
        Instant capturedAt,
        long pausedDurationMs,
        ExecutionStatistics statistics,
        List<StepReportEntry> steps,
        List<ReportErrorEntry> errors,
        int totalErrors,
        ExecutionReportResponse.Thresholds thresholds,
        ExecutionTimelineResponse timeline,
        ExecutionExtremesResponse fastest,
        ExecutionExtremesResponse slowest
) {
    public static final int VERSION = 1;
    public static final int MAX_ERRORS = 500;

    public boolean errorsTruncated() {
        return errors != null && totalErrors > errors.size();
    }
}
