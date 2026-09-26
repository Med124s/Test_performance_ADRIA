package com.loadpilot.backend.service.report;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * P1-C — extrait la formule de throughput (requetes reellement executees /
 * duree en secondes), jusqu'ici dupliquee a l'identique dans
 * MetricGenerationService ET PerformanceStatisticsService (chacune
 * documentant deja explicitement "meme formule que l'autre") - UNE seule
 * implementation reelle desormais, reutilisee aussi par ExecutionMapper
 * (voir MapperSupport#throughputOf) pour renseigner
 * ExecutionHistoryResponse.throughput. Comportement STRICTEMENT identique
 * a avant (memes arrondis/echelles) - aucune regression sur les Metric
 * deja generees ni sur les rapports P1-A.
 */
public final class ThroughputCalculator {

    private ThroughputCalculator() {
    }

    /** Null si duree ou nombre de requetes absent/nul - jamais une division par zero. */
    public static BigDecimal compute(int executedRequests, Long durationMs) {
        if (durationMs == null || durationMs <= 0 || executedRequests <= 0) {
            return null;
        }
        BigDecimal seconds = BigDecimal.valueOf(durationMs).divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(executedRequests).divide(seconds, 3, RoundingMode.HALF_UP);
    }
}
