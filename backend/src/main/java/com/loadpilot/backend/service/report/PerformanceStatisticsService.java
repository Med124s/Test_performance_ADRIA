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

/**
 * P1-A — service d'agregation statistique UNIQUE et partage (prompt
 * section 29 : "évite de créer trois implémentations différentes du même
 * calcul") pour le Reporting. N'est PAS utilise par Dashboard/Metrics
 * existants (voir rapport P1-A, section "Architecture avant/après") :
 * ceux-ci utilisent deja leurs propres requetes d'agregation SQL
 * (AVG/COUNT sur Metric, voir MetricRepository/DashboardServiceImpl),
 * deja valides et non modifiees ici (aucune regression volontaire) - mais
 * suivent EXACTEMENT les memes formules throughput/errorRate (voir
 * ExecutionStatistics), verifiees et documentees comme identiques.
 *
 * OPTION CHOISIE (prompt section 14) : calcul A LA DEMANDE depuis
 * ExecutionStepResult (Option A), jamais une table d'agregation persistee.
 * JUSTIFICATION : ExecutionStepResult contient deja 100% des donnees
 * necessaires (temps de reponse, succes, step, timestamp) sans perte ;
 * calculer a la demande evite toute derive entre deux sources dupliquees
 * (contrairement a Metric, qui duplique deja responseTime a des fins
 * d'affichage simple - non reutilise ici pour ne pas dependre d'un effet
 * de bord (la generation de Metric peut echouer sans bloquer l'Execution,
 * voir ExecutionServiceImpl.runAsync) ; le volume actuel (au plus quelques
 * milliers de lignes par Execution, voir benchmark P0-B jusqu'a 100 VUs)
 * ne justifie aucune agregation persistee - calcul en memoire, instantane.
 */
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
                PercentileCalculator.stdDev(responseTimes),
                throughput(total, executionDurationMs));
    }

    /**
     * Regroupe par Step reel (jamais par nom, pour ne jamais confondre deux
     * Steps de meme nom sur des Scenario differents) - un Step peut
     * apparaitre plusieurs fois si virtualUsers/iterations > 1, voir prompt
     * section 20. L'ordre de sortie suit l'ordre de premiere apparition
     * (LinkedHashMap), lui-meme deja chronologique (voir
     * ExecutionStepResultRepository.findByExecutionIdOrderByTimestampAsc).
     */
    public List<StepReportEntry> computeStepBreakdown(List<ExecutionStepResult> results) {
        // Regroupement par ID (jamais par l'entite Step elle-meme comme cle
        // de Map - eviterait de dependre implicitement de l'identity-map
        // Hibernate pour l'egalite, un detail d'implementation fragile).
        Map<UUID, Step> stepById = new LinkedHashMap<>();
        Map<UUID, List<ExecutionStepResult>> resultsByStepId = new LinkedHashMap<>();
        for (ExecutionStepResult result : results) {
            UUID stepId = result.getStep().getId();
            stepById.putIfAbsent(stepId, result.getStep());
            resultsByStepId.computeIfAbsent(stepId, k -> new ArrayList<>()).add(result);
        }

        List<StepReportEntry> entries = new ArrayList<>();
        for (Map.Entry<UUID, List<ExecutionStepResult>> entry : resultsByStepId.entrySet()) {
            Step step = stepById.get(entry.getKey());
            List<ExecutionStepResult> stepResults = entry.getValue();
            int total = stepResults.size();
            int successCount = (int) stepResults.stream().filter(r -> Boolean.TRUE.equals(r.getSuccess())).count();
            List<Long> responseTimes = stepResults.stream().map(ExecutionStepResult::getResponseTime).toList();

            entries.add(new StepReportEntry(
                    step.getId().toString(),
                    step.getName(),
                    step.getMethod(),
                    step.getUrl(),
                    total,
                    successCount,
                    total - successCount,
                    PercentileCalculator.average(responseTimes),
                    PercentileCalculator.min(responseTimes),
                    PercentileCalculator.max(responseTimes),
                    PercentileCalculator.percentile(responseTimes, 95),
                    PercentileCalculator.percentile(responseTimes, 99),
                    PercentileCalculator.stdDev(responseTimes),
                    rate(total - successCount, total)));
        }
        return entries;
    }

    public List<ReportErrorEntry> extractErrors(List<ExecutionStepResult> results) {
        return results.stream()
                .filter(r -> !Boolean.TRUE.equals(r.getSuccess()))
                .map(r -> new ReportErrorEntry(
                        r.getStep().getName(),
                        r.getStep().getMethod(),
                        r.getStep().getUrl(),
                        r.getHttpStatus(),
                        r.getError(),
                        r.getTimestamp()))
                .collect(Collectors.toList());
    }

    /** Meme formule que MetricGenerationService.computeErrorRate (voir sa
     * Javadoc) : count/total * 100, null si total == 0 (jamais de division
     * par zero, jamais un taux invente sur une execution vide). */
    private BigDecimal rate(int count, int total) {
        if (total <= 0) {
            return null;
        }
        return BigDecimal.valueOf(count)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }

    /** P1-C — delegue a ThroughputCalculator (voir sa Javadoc) : meme
     * formule exacte que MetricGenerationService, desormais partagee au
     * lieu de dupliquee. */
    private BigDecimal throughput(int total, Long durationMs) {
        return ThroughputCalculator.compute(total, durationMs);
    }
}
