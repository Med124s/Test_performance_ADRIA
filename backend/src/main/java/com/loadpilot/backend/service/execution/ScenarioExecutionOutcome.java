package com.loadpilot.backend.service.execution;

import com.loadpilot.backend.enums.ExecutionStatus;
import java.util.List;

/** Resultat du moteur : statut (SUCCESS ou FAILED), resultats restant a enregistrer et totaux. */
public record ScenarioExecutionOutcome(
        List<StepOutcome> stepOutcomes,
        ExecutionStatus finalStatus,
        String errorMessage,
        ResultSummary summary
) {
    /** Totaux calcules sur la liste des resultats. */
    public ScenarioExecutionOutcome(List<StepOutcome> stepOutcomes, ExecutionStatus finalStatus, String errorMessage) {
        this(stepOutcomes, finalStatus, errorMessage, ResultSummary.of(stepOutcomes));
    }
}
