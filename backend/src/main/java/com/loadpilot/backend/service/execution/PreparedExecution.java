package com.loadpilot.backend.service.execution;

import com.loadpilot.backend.enums.RequestDetailMode;
import com.loadpilot.backend.service.auth.ApplicationAuthSpec;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Donnees necessaires pour lancer une execution, copiees pendant sa preparation. */
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
        RequestDetailMode requestDetailMode,
        /** Frein automatique ; null = desactive. */
        ErrorRateBrake.Config errorBrake,
        /** Reglages reseau pour joindre l'application ; null = proxy du serveur. */
        com.loadpilot.backend.service.http.NetworkProfile network,
        /** Serie d'essais de stress de l'execution ; null hors serie. */
        UUID seriesId
) {
    public PreparedExecution(UUID executionId, List<StepExecutionSpec> steps, String applicationBaseUrl, ApplicationAuthSpec auth,
            LoadTestSpec loadSpec, List<Map<String, String>> vuVariableRows, VariableSources variableSources, String scenarioName,
            UUID triggeredByAppUserId, UUID scheduleId, RequestDetailMode requestDetailMode, ErrorRateBrake.Config errorBrake,
            com.loadpilot.backend.service.http.NetworkProfile network) {
        this(executionId, steps, applicationBaseUrl, auth, loadSpec, vuVariableRows, variableSources, scenarioName,
                triggeredByAppUserId, scheduleId, requestDetailMode, errorBrake, network, null);
    }

    public PreparedExecution(UUID executionId, List<StepExecutionSpec> steps, String applicationBaseUrl, ApplicationAuthSpec auth,
            LoadTestSpec loadSpec, List<Map<String, String>> vuVariableRows, VariableSources variableSources, String scenarioName,
            UUID triggeredByAppUserId, UUID scheduleId, RequestDetailMode requestDetailMode, ErrorRateBrake.Config errorBrake) {
        this(executionId, steps, applicationBaseUrl, auth, loadSpec, vuVariableRows, variableSources, scenarioName,
                triggeredByAppUserId, scheduleId, requestDetailMode, errorBrake, null);
    }

    public PreparedExecution(UUID executionId, List<StepExecutionSpec> steps, String applicationBaseUrl, ApplicationAuthSpec auth,
            LoadTestSpec loadSpec, List<Map<String, String>> vuVariableRows, VariableSources variableSources, String scenarioName,
            UUID triggeredByAppUserId, UUID scheduleId) {
        this(executionId, steps, applicationBaseUrl, auth, loadSpec, vuVariableRows, variableSources, scenarioName,
                triggeredByAppUserId, scheduleId, RequestDetailMode.NONE, null);
    }
}
