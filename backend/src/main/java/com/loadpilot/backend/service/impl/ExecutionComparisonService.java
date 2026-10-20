package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.dto.response.ExecutionComparisonResponse;
import com.loadpilot.backend.dto.response.ExecutionComparisonResponse.Column;
import com.loadpilot.backend.dto.response.ExecutionComparisonResponse.StepRow;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.entity.ExecutionStepResult;
import com.loadpilot.backend.exception.BadRequestException;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.ExecutionStepResultRepository;
import com.loadpilot.backend.service.report.ExecutionStatistics;
import com.loadpilot.backend.service.report.PerformanceStatisticsService;
import com.loadpilot.backend.service.report.StepReportEntry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * « Comparer des executions » : 2 a 4 executions terminees, chacune calculee EXACTEMENT comme son
 * rapport (PerformanceStatisticsService, debit hors pauses) - les colonnes et les rapports affichent
 * toujours les memes valeurs. Colonnes dans l'ordre chronologique ; etapes rapprochees par nom et
 * methode (ordre de premiere apparition).
 */
@Service
@RequiredArgsConstructor
public class ExecutionComparisonService {

    public static final int MIN_EXECUTIONS = 2;
    public static final int MAX_EXECUTIONS = 4;

    private final ExecutionRepository executionRepository;
    private final ExecutionStepResultRepository resultRepository;
    private final PerformanceStatisticsService statisticsService;
    private final ReportSnapshots reportSnapshots;
    private final ResultLoadGuard resultLoadGuard;

    @Transactional(readOnly = true)
    public ExecutionComparisonResponse compare(List<UUID> ids) {
        List<UUID> distinct = new ArrayList<>(new LinkedHashSet<>(ids == null ? List.of() : ids));
        if (distinct.size() < MIN_EXECUTIONS || distinct.size() > MAX_EXECUTIONS) {
            throw new BadRequestException("Choisissez de " + MIN_EXECUTIONS + " à " + MAX_EXECUTIONS + " exécutions différentes à comparer.");
        }
        List<Execution> executions = new ArrayList<>();
        for (UUID id : distinct) {
            Execution e = executionRepository.findByIdWithScenarioAndApplication(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Exécution introuvable : " + id));
            if (e.getStatus().isActive()) {
                throw new BadRequestException("L'exécution du scénario « " + e.getScenario().getName()
                        + " » n'est pas terminée : seules les exécutions terminées peuvent être comparées.");
            }
            executions.add(e);
        }
        executions.sort(Comparator.comparing(Execution::getStartedAt));

        // Resultats de chaque execution comparee (sauf rapport fige) charges et gardes jusqu'a la fin :
        // memoire reservee pour leur somme (voir ResultLoadGuard).
        long results = 0;
        for (Execution e : executions) {
            if (!ReportSnapshots.purged(e)) results += resultRepository.countAllByExecutionId(e.getId());
        }
        try (ResultLoadGuard.Reservation r = resultLoadGuard.reserve(ResultLoadGuard.Load.COMPARE, results)) {
            return compareLoaded(executions);
        }
    }

    private ExecutionComparisonResponse compareLoaded(List<Execution> executions) {

        List<Column> columns = new ArrayList<>();
        Map<String, Long[]> stepP95 = new LinkedHashMap<>();
        Map<String, String[]> stepInfo = new LinkedHashMap<>();
        for (int i = 0; i < executions.size(); i++) {
            Execution e = executions.get(i);
            // Detail des requetes supprime : statistiques et etapes du rapport fige (memes valeurs que le rapport).
            com.loadpilot.backend.service.report.ReportSnapshot frozen = ReportSnapshots.purged(e)
                    ? reportSnapshots.read(e).orElse(null) : null;
            List<ExecutionStepResult> results = frozen != null ? List.of() : resultRepository.findByExecutionIdOrderByTimestampAsc(e.getId());
            long pausedMs = frozen != null ? frozen.pausedDurationMs() : ExecutionServiceImpl.pausedDurationMs(e, Instant.now());
            Long activeMs = e.getDuration() != null ? Math.max(0L, e.getDuration() - pausedMs) : null;
            ExecutionStatistics s = frozen != null ? frozen.statistics() : statisticsService.computeOverallStatistics(results, activeMs);
            columns.add(new Column(e.getId().toString(), e.getScenario().getId().toString(), e.getScenario().getName(),
                    e.getScenario().getApplication().getName(), e.getStartedAt(), e.getStatus(), e.getVirtualUsers(), e.getRampUpSeconds(),
                    e.getDurationSeconds(), e.getIterations(), activeMs, s.totalRequests(), s.throughput(), s.p95(), s.p99(), s.errorRate(),
                    e.getCpuMaxPercent(), e.getMemoryMaxMb()));
            for (StepReportEntry step : frozen != null ? frozen.steps() : statisticsService.computeStepBreakdown(results)) {
                String method = step.method() == null ? null : step.method().name();
                String key = method + " " + step.stepName();
                stepInfo.putIfAbsent(key, new String[] {step.stepName(), method});
                stepP95.computeIfAbsent(key, k -> new Long[executions.size()])[i] = step.p95();
            }
        }
        List<StepRow> steps = new ArrayList<>();
        stepP95.forEach((key, values) -> steps.add(new StepRow(stepInfo.get(key)[0], stepInfo.get(key)[1], java.util.Arrays.asList(values))));

        long scenarios = columns.stream().map(Column::scenarioId).distinct().count();
        String warning = scenarios > 1
                ? "Ces exécutions n'utilisent pas le même scénario (" + scenarios + " scénarios différents) : les étapes et la charge "
                        + "ne sont pas identiques, la comparaison est à interpréter avec prudence."
                : null;
        return new ExecutionComparisonResponse(scenarios == 1, warning, columns, steps);
    }
}
