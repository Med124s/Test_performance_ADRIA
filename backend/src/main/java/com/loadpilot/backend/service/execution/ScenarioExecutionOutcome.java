package com.loadpilot.backend.service.execution;

import com.loadpilot.backend.enums.ExecutionStatus;
import java.util.List;

/** finalStatus est toujours SUCCESS ou FAILED ici (jamais RUNNING/CANCELLED,
 * ces deux-la sont geres par le service, pas par l'engine). */
public record ScenarioExecutionOutcome(
        List<StepOutcome> stepOutcomes,
        ExecutionStatus finalStatus,
        String errorMessage
) {
}
