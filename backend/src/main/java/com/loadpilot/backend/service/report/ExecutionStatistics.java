package com.loadpilot.backend.service.report;

import java.math.BigDecimal;

/**
 * Statistiques REELLEMENT calculees (voir PerformanceStatisticsService) a
 * partir des ExecutionStepResult d'une Execution - jamais une valeur
 * inventee. Tous les temps sont en MILLISECONDES. "throughput"/"errorRate"
 * suivent EXACTEMENT les memes formules que MetricGenerationService (voir
 * sa Javadoc) pour rester cohérentes partout dans LoadPilot (prompt P1-A,
 * section 18) :
 *   throughput = totalRequests / dureeExecution(secondes)
 *   errorRate  = failedRequests / totalRequests * 100
 *
 * Tous les champs numeriques sont null quand non calculables (population
 * vide, duree absente...) - jamais une valeur fabriquee (0, NaN...).
 */
public record ExecutionStatistics(
        int totalRequests,
        int successfulRequests,
        int failedRequests,
        BigDecimal successRate,
        BigDecimal errorRate,
        Long minResponseTime,
        Long maxResponseTime,
        Long avgResponseTime,
        Long p50,
        Long p75,
        Long p90,
        Long p95,
        Long p99,
        /** P1-C — ecart-type de population des temps de reponse (voir
         * PercentileCalculator#stdDev) - null si population vide, 0 si une
         * seule valeur (valeur reelle, pas une absence de donnee). */
        Long stdDevResponseTime,
        BigDecimal throughput
) {
}
