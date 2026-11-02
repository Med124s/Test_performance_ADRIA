package com.loadpilot.backend.service.execution;

import com.loadpilot.backend.enums.ExecutionStatus;
import java.text.NumberFormat;
import java.util.Locale;

/** Statut final d'une execution terminee : CANCELLED, FAILED (test qui n'a pas pu se derouler), THRESHOLDS_EXCEEDED ou SUCCESS. */
public final class ExecutionVerdict {

    public static final String UNREACHABLE = "Application injoignable : aucune requête n'a reçu de réponse";

    public record Result(ExecutionStatus status, String errorMessage) {
    }

    private ExecutionVerdict() {
    }

    public static Result decide(ScenarioExecutionOutcome outcome, boolean cancelled, String cancelMessage,
                                Double maxErrorRatePercent, Long maxP95Ms) {
        if (cancelled) return new Result(ExecutionStatus.CANCELLED, cancelMessage);
        if (maxErrorRatePercent == null) return new Result(outcome.finalStatus(), outcome.errorMessage());
        // Totaux de toutes les requetes de l'execution.
        ResultSummary all = outcome.summary();
        if (all.total() == 0) {
            return new Result(ExecutionStatus.FAILED,
                    outcome.errorMessage() != null ? outcome.errorMessage() : "Aucune requête n'a été exécutée.");
        }

        if (!all.anyResponse()) {
            String first = all.firstOutcomeError();
            return new Result(ExecutionStatus.FAILED, UNREACHABLE + (first != null ? " (" + first + ")" : "") + ".");
        }

        long failures = all.failures();
        double errorRate = failures * 100.0 / all.total();
        Long p95 = all.percentile(95);
        boolean errorsExceeded = errorRate > maxErrorRatePercent;
        boolean p95Exceeded = maxP95Ms != null && p95 != null && p95 > maxP95Ms;
        if (!errorsExceeded && !p95Exceeded) return new Result(ExecutionStatus.SUCCESS, null);

        StringBuilder message = new StringBuilder("Seuils dépassés : ");
        if (errorsExceeded) {
            message.append("taux d'erreurs ").append(format(errorRate)).append(" % > ").append(format(maxErrorRatePercent)).append(" %");
        }
        if (p95Exceeded) {
            if (errorsExceeded) message.append(" ; ");
            message.append("P95 ").append(format(p95)).append(" ms > ").append(format(maxP95Ms)).append(" ms");
        }
        return new Result(ExecutionStatus.THRESHOLDS_EXCEEDED, message.append('.').toString());
    }

    /** Taux d'erreurs (%) arrondi a 0,1. */
    public static double roundedRate(long failures, long total) {
        return total == 0 ? 0.0 : Math.round(failures * 1000.0 / total) / 10.0;
    }

    static String format(Number value) {
        NumberFormat f = NumberFormat.getNumberInstance(Locale.FRANCE);
        f.setMaximumFractionDigits(1);
        return f.format(value);
    }
}
