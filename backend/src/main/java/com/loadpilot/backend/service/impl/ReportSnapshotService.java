package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.dto.response.ExecutionExtremesResponse;
import com.loadpilot.backend.dto.response.ExecutionReportResponse;
import com.loadpilot.backend.dto.response.ExecutionTimelineResponse;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.entity.ExecutionReportSnapshot;
import com.loadpilot.backend.repository.ExecutionReportSnapshotRepository;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.service.ExecutionService;
import com.loadpilot.backend.service.execution.RunningExecutionRegistry;
import com.loadpilot.backend.service.report.ReportErrorEntry;
import com.loadpilot.backend.service.report.ReportSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Rapport fige : calcule une fois, a la fin de l'execution, EXACTEMENT ce que montrent le rapport,
 * l'analyse dans le temps et les cartes Minimum / Maximum (memes services), puis l'enregistre dans
 * execution_report_snapshot. Il remplace ces calculs une fois le detail des requetes purge.
 *
 * Rattrapage : au demarrage (executions terminees sans rapport fige, en arriere-plan) et juste
 * avant chaque purge (jamais de purge sans rapport fige).
 */
@Service
@RequiredArgsConstructor
public class ReportSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(ReportSnapshotService.class);
    /** Taille maximale enregistree (colonne VARCHAR(1000000)). */
    static final int MAX_LENGTH = 1_000_000;

    private final ExecutionRepository executionRepository;
    private final ExecutionReportSnapshotRepository snapshotRepository;
    private final ExecutionService executionService;
    private final ExecutionTimelineService timelineService;
    private final ExecutionExtremesService extremesService;
    private final ReportSnapshots reportSnapshots;
    private final RunningExecutionRegistry registry;

    @Value("${app.results.snapshot-backfill.enabled:true}")
    private boolean backfillEnabled;

    /**
     * Calcule et enregistre (ou remplace) le rapport fige d'une execution terminee dont le detail
     * est encore present. Renvoie false si l'execution est en cours, absente ou deja purgee.
     */
    public boolean capture(UUID executionId) {
        Execution execution = executionRepository.findById(executionId).orElse(null);
        if (execution == null || execution.getStatus() == null || execution.getStatus().isActive()
                || ReportSnapshots.purged(execution)) {
            return false;
        }
        ExecutionReportResponse report = executionService.getReport(executionId);
        ExecutionTimelineResponse timeline = timelineService.timeline(executionId);
        ExecutionExtremesResponse fastest = extremesService.extremes(executionId, false);
        ExecutionExtremesResponse slowest = extremesService.extremes(executionId, true);

        List<ReportErrorEntry> errors = report.errors() == null ? List.of() : report.errors();
        int totalErrors = errors.size();
        String json = serialize(report, errors, totalErrors, ReportSnapshot.MAX_ERRORS, timeline, fastest, slowest);
        // Trop volumineux (tres longue execution) : moins d'erreurs, puis sans l'analyse dans le temps.
        if (json != null && json.length() > MAX_LENGTH) json = serialize(report, errors, totalErrors, 50, timeline, fastest, slowest);
        if (json != null && json.length() > MAX_LENGTH) json = serialize(report, errors, totalErrors, 50, null, fastest, slowest);
        if (json == null || json.length() > MAX_LENGTH) {
            log.warn("Rapport fige trop volumineux pour l'execution {} : non enregistre", executionId);
            return false;
        }
        snapshotRepository.save(new ExecutionReportSnapshot(executionId, json, Instant.now()));
        return true;
    }

    private String serialize(ExecutionReportResponse report, List<ReportErrorEntry> errors, int totalErrors, int maxErrors,
            ExecutionTimelineResponse timeline, ExecutionExtremesResponse fastest, ExecutionExtremesResponse slowest) {
        ReportSnapshot snapshot = new ReportSnapshot(ReportSnapshot.VERSION, Instant.now(), report.pausedDurationMs(),
                report.statistics(), report.steps(), errors.size() > maxErrors ? List.copyOf(errors.subList(0, maxErrors)) : errors,
                totalErrors, report.thresholds(), timeline, fastest, slowest);
        try {
            return reportSnapshots.write(snapshot);
        } catch (Exception e) {
            log.warn("Rapport fige non serialisable : {}", e.getClass().getSimpleName());
            return null;
        }
    }

    /** Rapport fige present, ou calcule maintenant ; false si impossible (la purge est alors reportee). */
    public boolean ensure(UUID executionId) {
        if (reportSnapshots.exists(executionId)) return true;
        try {
            return capture(executionId);
        } catch (RuntimeException e) {
            log.warn("Rapport fige impossible pour l'execution {} : {}", executionId, e.getClass().getSimpleName());
            return false;
        }
    }

    /** Executions terminees sans rapport fige (detail encore present) : calcule une fois. */
    public int backfill() {
        int done = 0;
        for (UUID id : executionRepository.findFinishedIdsWithoutSnapshot()) {
            if (registry.get(id) != null) continue; // tache asynchrone pas encore terminee
            if (ensure(id)) done++;
        }
        if (done > 0) log.info("Rapport fige calcule pour {} execution(s) existante(s)", done);
        return done;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnStartup() {
        if (!backfillEnabled) return;
        Thread thread = new Thread(() -> {
            try {
                backfill();
            } catch (RuntimeException e) {
                log.warn("Rattrapage des rapports figes interrompu : {}", e.getClass().getSimpleName());
            }
        }, "report-snapshot-backfill");
        thread.setDaemon(true);
        thread.start();
    }
}
