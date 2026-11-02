package com.loadpilot.backend.service.execution;

import com.loadpilot.backend.service.auth.ApplicationAuthSpec;
import java.util.List;
import java.util.Map;

/** Moteur d'execution d'un scenario. */
public interface ExecutionEngine {

    /** Execute les etapes avec la charge de {@code loadSpec} ; un echec (HTTP, reseau, timeout, annulation) est renvoye dans le resultat. */
    default ScenarioExecutionOutcome execute(List<StepExecutionSpec> steps, String applicationBaseUrl,
            LoadTestSpec loadSpec, RunningExecutionHandle handle, List<Map<String, String>> vuVariableRows) {
        return execute(steps, applicationBaseUrl, ApplicationAuthSpec.none(), loadSpec, handle, vuVariableRows);
    }

    /** Variante avec l'authentification de l'application ; pour LOGIN_TOKEN, la connexion a lieu une fois au debut. */
    ScenarioExecutionOutcome execute(List<StepExecutionSpec> steps, String applicationBaseUrl, ApplicationAuthSpec auth,
            LoadTestSpec loadSpec, RunningExecutionHandle handle, List<Map<String, String>> vuVariableRows);

    /** Variante avec les sources de variables (CSV, variables du scenario). */
    default ScenarioExecutionOutcome execute(List<StepExecutionSpec> steps, String applicationBaseUrl, ApplicationAuthSpec auth,
            LoadTestSpec loadSpec, RunningExecutionHandle handle, VariableSources sources) {
        return execute(steps, applicationBaseUrl, auth, loadSpec, handle, sources,
                com.loadpilot.backend.service.http.NetworkProfile.inherit());
    }

    /** Variante avec les reglages reseau de l'application ; null = proxy du serveur. */
    ScenarioExecutionOutcome execute(List<StepExecutionSpec> steps, String applicationBaseUrl, ApplicationAuthSpec auth,
            LoadTestSpec loadSpec, RunningExecutionHandle handle, VariableSources sources,
            com.loadpilot.backend.service.http.NetworkProfile network);
}
