package com.loadpilot.backend.service.execution;

import com.loadpilot.backend.enums.ExecutionStatus;
import java.util.List;

/**
 * finalStatus est toujours SUCCESS ou FAILED ici (jamais RUNNING/CANCELLED, ces deux-la sont geres
 * par le service, pas par l'engine).
 *
 * stepOutcomes : resultats encore a enregistrer. Vide quand ils ont ete ecrits en base pendant le test
 * (ResultStream) ; summary couvre alors TOUS les resultats de l'execution, ecrits ou non.
 */
public record ScenarioExecutionOutcome(
        List<StepOutcome> stepOutcomes,
        ExecutionStatus finalStatus,
        String errorMessage,
        ResultSummary summary
) {
    /** Resultats tous en memoire (sans ecriture au fil de l'eau) : totaux calcules sur la liste. */
    public ScenarioExecutionOutcome(List<StepOutcome> stepOutcomes, ExecutionStatus finalStatus, String errorMessage) {
        this(stepOutcomes, finalStatus, errorMessage, ResultSummary.of(stepOutcomes));
    }
}
