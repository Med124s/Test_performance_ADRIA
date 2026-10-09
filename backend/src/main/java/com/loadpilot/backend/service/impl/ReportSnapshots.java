package com.loadpilot.backend.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.dto.response.ExecutionExtremesResponse;
import com.loadpilot.backend.dto.response.ExecutionRequestRowResponse;
import com.loadpilot.backend.dto.response.ResultsRetentionResponse;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.repository.ExecutionReportSnapshotRepository;
import com.loadpilot.backend.service.AppSettingsService;
import com.loadpilot.backend.service.report.ReportSnapshot;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Lecture du rapport fige (table execution_report_snapshot) et etat de conservation d'une execution.
 * Aucune dependance vers les services de calcul : utilisable par le rapport, l'analyse dans le
 * temps, les cartes Minimum / Maximum et la comparaison.
 */
@Component
@RequiredArgsConstructor
public class ReportSnapshots {

    private static final Logger log = LoggerFactory.getLogger(ReportSnapshots.class);

    private final ObjectMapper objectMapper;
    private final AppSettingsService settings;
    private final ExecutionReportSnapshotRepository repository;

    /** Vrai si le rapport doit venir du rapport fige : detail des requetes supprime. */
    public static boolean purged(Execution execution) {
        return execution.getResultsPurgedAt() != null;
    }

    public Optional<ReportSnapshot> read(Execution execution) {
        return read(execution.getId());
    }

    public Optional<ReportSnapshot> read(java.util.UUID executionId) {
        return repository.findById(executionId).flatMap(row -> {
            try {
                return Optional.of(objectMapper.readValue(row.getSnapshot(), ReportSnapshot.class));
            } catch (Exception e) {
                log.warn("Rapport fige illisible pour l'execution {} : {}", executionId, e.getClass().getSimpleName());
                return Optional.empty();
            }
        });
    }

    public boolean exists(java.util.UUID executionId) {
        return repository.existsById(executionId);
    }

    public String write(ReportSnapshot snapshot) throws com.fasterxml.jackson.core.JsonProcessingException {
        return objectMapper.writeValueAsString(snapshot);
    }

    /** Requetes extremes du rapport fige : leur detail (en-tetes, corps) n'existe plus. */
    public static ExecutionExtremesResponse withoutDetail(ExecutionExtremesResponse extremes) {
        if (extremes == null) return null;
        return new ExecutionExtremesResponse(extremes.kind(), extremes.cardValueMs(), extremes.measuredRequests(), extremes.intervalSeconds(),
                extremes.requests().stream().map(r -> {
                    ExecutionRequestRowResponse q = r.request();
                    ExecutionRequestRowResponse row = new ExecutionRequestRowResponse(q.resultId(), q.stepId(), q.stepName(), q.method(),
                            q.virtualUser(), q.iteration(), q.timestamp(), q.httpStatus(), q.responseTime(), q.success(), q.error(),
                            q.requestUrl(), null);
                    return new ExecutionExtremesResponse.ExtremeRequest(row, r.secondsFromStart(), r.intervalStartSeconds(),
                            r.activeVirtualUsers(), r.throughput(), r.p95Ms());
                }).toList());
    }

    public ResultsRetentionResponse retention(Execution execution, boolean fromSnapshot, boolean errorsTruncated) {
        return new ResultsRetentionResponse(execution.isKeepResults(), execution.getResultsPurgedAt(),
                execution.getRequestDetailsPurgedAt(), fromSnapshot, notice(execution, errorsTruncated));
    }

    /** Mention affichee quand une partie du detail a ete supprimee (null sinon). */
    public String notice(Execution execution, boolean errorsTruncated) {
        if (execution.getResultsPurgedAt() != null) {
            return "Détail des requêtes supprimé après " + settings.resultsRetentionDays() + " jours, résumé conservé."
                    + (errorsTruncated ? " Seules les " + ReportSnapshot.MAX_ERRORS + " premières erreurs sont conservées." : "");
        }
        if (execution.getRequestDetailsPurgedAt() != null) {
            return "En-têtes et corps des requêtes supprimés après " + settings.requestDetailsRetentionDays()
                    + " jours ; la liste des requêtes et le rapport restent complets.";
        }
        return null;
    }
}
