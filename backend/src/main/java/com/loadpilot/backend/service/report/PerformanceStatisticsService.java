package com.loadpilot.backend.service.report;

import com.loadpilot.backend.entity.ExecutionStepResult;
import com.loadpilot.backend.entity.Step;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Statistiques du rapport d'une execution : resume, detail par etape et erreurs. */
@Service
public class PerformanceStatisticsService {

    public ExecutionStatistics computeOverallStatistics(List<ExecutionStepResult> results, Long executionDurationMs) {
        int total = results.size();
        int successCount = (int) results.stream().filter(r -> Boolean.TRUE.equals(r.getSuccess())).count();
        int failedCount = total - successCount;
        List<Long> responseTimes = results.stream().map(ExecutionStepResult::getResponseTime).toList();

        return new ExecutionStatistics(
                total,
                successCount,
                failedCount,
                rate(successCount, total),
                rate(failedCount, total),
                PercentileCalculator.min(responseTimes),
                PercentileCalculator.max(responseTimes),
                PercentileCalculator.average(responseTimes),
                PercentileCalculator.percentile(responseTimes, 50),
                PercentileCalculator.percentile(responseTimes, 75),
                PercentileCalculator.percentile(responseTimes, 90),
                PercentileCalculator.percentile(responseTimes, 95),
                PercentileCalculator.percentile(responseTimes, 99),
                throughput(total, executionDurationMs));
    }

    /** Statistiques par etape, dans l'ordre de premiere apparition. */
    public List<StepReportEntry> computeStepBreakdown(List<ExecutionStepResult> results) {
        // Regroupement par id d'etape ; etape supprimee depuis : regroupement par sa copie.
        Map<String, ExecutionStepResult> firstByKey = new LinkedHashMap<>();
        Map<String, List<ExecutionStepResult>> resultsByKey = new LinkedHashMap<>();
        for (ExecutionStepResult result : results) {
            UUID stepId = result.stepIdOrNull();
            String key = stepId != null ? stepId.toString()
                    : "deleted:" + result.displayStepName() + "|" + result.displayStepMethod() + "|" + result.displayStepUrl();
            firstByKey.putIfAbsent(key, result);
            resultsByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(result);
        }

        List<StepReportEntry> entries = new ArrayList<>();
        for (Map.Entry<String, List<ExecutionStepResult>> entry : resultsByKey.entrySet()) {
            ExecutionStepResult first = firstByKey.get(entry.getKey());
            List<ExecutionStepResult> stepResults = entry.getValue();
            int total = stepResults.size();
            int successCount = (int) stepResults.stream().filter(r -> Boolean.TRUE.equals(r.getSuccess())).count();
            List<Long> responseTimes = stepResults.stream().map(ExecutionStepResult::getResponseTime).toList();

            entries.add(new StepReportEntry(
                    first.stepIdOrNull() != null ? first.stepIdOrNull().toString() : null,
                    first.displayStepName(),
                    first.displayStepMethod(),
                    first.displayStepUrl(),
                    total,
                    successCount,
                    total - successCount,
                    PercentileCalculator.average(responseTimes),
                    PercentileCalculator.min(responseTimes),
                    PercentileCalculator.max(responseTimes),
                    PercentileCalculator.percentile(responseTimes, 95),
                    PercentileCalculator.percentile(responseTimes, 99),
                    rate(total - successCount, total)));
        }
        return entries;
    }

    public List<ReportErrorEntry> extractErrors(List<ExecutionStepResult> results) {
        return results.stream()
                .filter(r -> !Boolean.TRUE.equals(r.getSuccess()))
                .map(r -> new ReportErrorEntry(
                        r.displayStepName(),
                        r.displayStepMethod(),
                        r.displayStepUrl(),
                        r.getHttpStatus(),
                        r.getError(),
                        r.getTimestamp()))
                .collect(Collectors.toList());
    }

    /** count / total * 100 ; null si total == 0. */
    private BigDecimal rate(int count, int total) {
        if (total <= 0) {
            return null;
        }
        return BigDecimal.valueOf(count)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal throughput(int total, Long durationMs) {
        return ThroughputCalculator.compute(total, durationMs);
    }
}
