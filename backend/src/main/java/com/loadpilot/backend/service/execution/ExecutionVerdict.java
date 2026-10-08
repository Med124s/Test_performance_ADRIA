package com.loadpilot.backend.service.execution;

import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.service.report.PercentileCalculator;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/**
 * Statut final d'une execution terminee, selon les seuils de reussite du scenario (copies sur
 * l'execution au lancement) :
 * - CANCELLED si elle a ete annulee ;
 * - FAILED seulement si le test n'a pas pu se derouler : aucune requete jouee (authentification
 *   refusee...) ou aucune requete n'a recu de reponse (application injoignable) ;
 * - THRESHOLDS_EXCEEDED si le test est alle au bout mais que le taux d'erreurs depasse le maximum,
 *   ou que le P95 (toutes requetes, meme calcul que le rapport) depasse le P95 maximal ;
 * - SUCCESS sinon.
 * Sans seuils (maxErrorRatePercent null : execution preparee avant les seuils), le statut du moteur
 * est garde tel quel (comportement d'origine).
 */
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
        List<StepOutcome> all = outcome.stepOutcomes();
        if (all.isEmpty()) {
            return new Result(ExecutionStatus.FAILED,
                    outcome.errorMessage() != null ? outcome.errorMessage() : "Aucune requête n'a été exécutée.");
        }

        if (all.stream().noneMatch(o -> o.httpStatus() != null)) {
            String first = all.get(0).error();
            return new Result(ExecutionStatus.FAILED, UNREACHABLE + (first != null ? " (" + first + ")" : "") + ".");
        }

        long failures = all.stream().filter(o -> !o.success()).count();
        double errorRate = failures * 100.0 / all.size();
        Long p95 = PercentileCalculator.percentile(all.stream().map(StepOutcome::responseTimeMs).toList(), 95);
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
