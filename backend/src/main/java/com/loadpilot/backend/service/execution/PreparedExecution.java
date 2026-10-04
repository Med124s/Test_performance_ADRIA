package com.loadpilot.backend.service.execution;

import com.loadpilot.backend.enums.RequestDetailMode;
import com.loadpilot.backend.service.auth.ApplicationAuthSpec;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tout ce qu'il faut pour lancer reellement une Execution, copie dans la transaction courte de
 * preparation (voir ExecutionTransactionHelper.prepareAndStart) - jamais une entite JPA geree
 * transportee hors de sa transaction.
 */
public record PreparedExecution(
        UUID executionId,
        List<StepExecutionSpec> steps,
        String applicationBaseUrl,
        ApplicationAuthSpec auth,
        LoadTestSpec loadSpec,
        List<Map<String, String>> vuVariableRows,
        VariableSources variableSources,
        String scenarioName,
        UUID triggeredByAppUserId,
        UUID scheduleId,
        /** Detail des requetes a conserver (voir RequestDetailRecorder). */
        RequestDetailMode requestDetailMode
) {
    /** Compatibilite : sans detail des requetes. */
    public PreparedExecution(UUID executionId, List<StepExecutionSpec> steps, String applicationBaseUrl, ApplicationAuthSpec auth,
            LoadTestSpec loadSpec, List<Map<String, String>> vuVariableRows, VariableSources variableSources, String scenarioName,
            UUID triggeredByAppUserId, UUID scheduleId) {
        this(executionId, steps, applicationBaseUrl, auth, loadSpec, vuVariableRows, variableSources, scenarioName,
                triggeredByAppUserId, scheduleId, RequestDetailMode.NONE);
    }
}
