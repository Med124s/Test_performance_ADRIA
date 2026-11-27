package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.service.report.ExecutionStatistics;
import com.loadpilot.backend.service.report.ReportErrorEntry;
import com.loadpilot.backend.service.report.StepReportEntry;
import java.time.Instant;
import java.util.List;

/** Rapport statistique d'une execution : resume, detail par etape et erreurs. */
public record ExecutionReportResponse(
        String id,
        String scenarioId,
        String scenarioName,
        String applicationId,
        String applicationName,
        ExecutionStatus status,
        Instant startedAt,
        Instant finishedAt,
        Long duration,
        String errorMessage,
        /** Parametres de charge figes au lancement. */
        Integer virtualUsers,
        Integer rampUpSeconds,
        Integer durationSeconds,
        Integer iterations,
        String triggeredByUsername,
        /** Temps total passe en pause (ms) ; le debit est calcule sans ce temps. */
        long pausedDurationMs,
        ExecutionStatistics statistics,
        List<StepReportEntry> steps,
        List<ReportErrorEntry> errors,
        /** Seuils de reussite de cette execution et leur resultat ; null = execution anterieure aux seuils. */
        Thresholds thresholds,
        /** Conservation : rapport issu du rapport fige si le detail des requetes a ete supprime. */
        ResultsRetentionResponse retention,
        /** Mode de charge, et resultat du test de stress (point de rupture), garde avec l'execution apres la purge. */
        com.loadpilot.backend.enums.LoadMode loadMode,
        StressResultResponse stress,
        /** Serie d'essais de stress de l'execution ; null hors serie. */
        StressSeriesRefResponse series
) {
    /** Execution en mode manuel. */
    public ExecutionReportResponse(String id, String scenarioId, String scenarioName, String applicationId, String applicationName,
            ExecutionStatus status, Instant startedAt, Instant finishedAt, Long duration, String errorMessage, Integer virtualUsers,
            Integer rampUpSeconds, Integer durationSeconds, Integer iterations, String triggeredByUsername, long pausedDurationMs,
            ExecutionStatistics statistics, List<StepReportEntry> steps, List<ReportErrorEntry> errors, Thresholds thresholds,
            ResultsRetentionResponse retention) {
        this(id, scenarioId, scenarioName, applicationId, applicationName, status, startedAt, finishedAt, duration, errorMessage,
                virtualUsers, rampUpSeconds, durationSeconds, iterations, triggeredByUsername, pausedDurationMs, statistics, steps,
                errors, thresholds, retention, com.loadpilot.backend.enums.LoadMode.MANUAL, null, null);
    }

    /** Seuil de taux d'erreurs (toujours present) et seuil de P95 (null = aucun) ; ok = seuil respecte. */
    public record Thresholds(double maxErrorRatePercent, double errorRatePercent, boolean errorRateOk,
                             Long maxP95Ms, Long p95Ms, Boolean p95Ok) {
    }
}
