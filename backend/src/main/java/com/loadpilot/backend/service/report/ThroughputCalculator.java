package com.loadpilot.backend.service.report;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Debit : requetes executees / duree en secondes. */
public final class ThroughputCalculator {

    private ThroughputCalculator() {
    }

    /** null si la duree ou le nombre de requetes est nul. */
    public static BigDecimal compute(int executedRequests, Long durationMs) {
        if (durationMs == null || durationMs <= 0 || executedRequests <= 0) {
            return null;
        }
        BigDecimal seconds = BigDecimal.valueOf(durationMs).divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(executedRequests).divide(seconds, 3, RoundingMode.HALF_UP);
    }
}
