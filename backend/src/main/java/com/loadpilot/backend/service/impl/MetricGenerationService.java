package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.entity.ExecutionStepResult;
import com.loadpilot.backend.entity.Metric;
import com.loadpilot.backend.entity.Scenario;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.ExecutionStepResultRepository;
import com.loadpilot.backend.repository.MetricRepository;
import com.loadpilot.backend.service.report.ThroughputCalculator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Genere automatiquement les Metric d'une Execution deja finalisee (voir
 * ExecutionServiceImpl.execute - appele APRES ExecutionTransactionHelper
 * .finalizeExecution, dans une transaction courte separee). Bean distinct
 * (meme raison que ExecutionTransactionHelper : @Transactional est ignore
 * lors d'un appel this.methode() depuis la meme classe).
 *
 * Ne modifie JAMAIS le statut d'une Execution : cette generation intervient
 * strictement APRES que l'Execution ait ete finalisee et committee -
 * l'appelant (ExecutionServiceImpl) encapsule en outre cet appel dans un
 * try/catch pour garantir qu'un echec de generation de Metric ne remonte
 * jamais au client HTTP ni n'affecte la reponse d'execution.
 */
@Service
@RequiredArgsConstructor
public class MetricGenerationService {

    private final ExecutionRepository executionRepository;
    private final ExecutionStepResultRepository executionStepResultRepository;
    private final MetricRepository metricRepository;

    @Transactional
    public void generateForExecution(UUID executionId) {
        Execution execution = executionRepository.findByIdWithScenarioAndApplication(executionId).orElse(null);
        if (execution == null) {
            return;
        }

        List<ExecutionStepResult> results = executionStepResultRepository.findByExecutionIdOrderByTimestampAsc(executionId);
        if (results.isEmpty()) {
            return;
        }

        Scenario scenario = execution.getScenario();
        Application application = scenario.getApplication();

        BigDecimal throughput = computeThroughput(results.size(), execution.getDuration());
        BigDecimal errorRate = computeErrorRate(execution.getFailedSteps(), results.size());

        for (ExecutionStepResult result : results) {
            Metric metric = Metric.builder()
                    .application(application)
                    .scenario(scenario)
                    .step(result.getStep())
                    .execution(execution)
                    .responseTime(result.getResponseTime() != null ? result.getResponseTime().intValue() : null)
                    .statusCode(result.getHttpStatus())
                    .throughput(throughput)
                    .errorRate(errorRate)
                    .timestamp(result.getTimestamp())
                    .build();
            metricRepository.save(metric);
        }
    }

    /**
     * Requetes/seconde = steps reellement executes / duree totale (secondes).
     * Null si la duree est absente ou nulle (aucune mesure de debit
     * possible), jamais une valeur inventee.
     */
    /** P1-C — delegue a ThroughputCalculator (voir sa Javadoc) : meme
     * formule exacte, desormais partagee au lieu de dupliquee. */
    private BigDecimal computeThroughput(int executedSteps, Long durationMs) {
        return ThroughputCalculator.compute(executedSteps, durationMs);
    }

    /**
     * errorRate = failedSteps / executedSteps * 100 - deliberement PAS
     * totalSteps (voir Metric.errorRate) puisque l'execution s'arrete au
     * premier echec (stop-on-failure, voir HttpClientExecutionEngine).
     */
    private BigDecimal computeErrorRate(int failedSteps, int executedSteps) {
        if (executedSteps <= 0) {
            return null;
        }
        return BigDecimal.valueOf(failedSteps)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(executedSteps), 2, RoundingMode.HALF_UP);
    }
}
