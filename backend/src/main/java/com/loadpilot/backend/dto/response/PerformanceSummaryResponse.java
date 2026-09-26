package com.loadpilot.backend.dto.response;

import java.math.BigDecimal;

/**
 * Moyennes calculees sur les Metric reelles (voir Phase 10) - null si
 * aucune Metric n'existe pour le perimetre demande (jamais 0 invente : une
 * moyenne de zero mesure est indefinie, pas "zero").
 *
 * averageResponseTime : millisecondes. averageThroughput : requetes/seconde.
 * averageErrorRate : pourcentage (0-100).
 */
public record PerformanceSummaryResponse(
        BigDecimal averageResponseTime,
        BigDecimal averageThroughput,
        BigDecimal averageErrorRate
) {
}
