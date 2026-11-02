package com.loadpilot.backend.service.execution;

import com.loadpilot.backend.service.auth.ApplicationAuthSpec;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

/** Donnees d'un test detaille, copiees avant le test. */
public record TestRunPlan(
        UUID scenarioId,
        String applicationBaseUrl,
        ApplicationAuthSpec auth,
        List<StepExecutionSpec> steps,
        Map<String, String> csvRow,
        Map<String, String> scenarioVariables,
        Map<String, String> providedVariables,
        List<Integer> stepNumbers,
        Map<String, Integer> captureStepByVariable,
        /** Reglages reseau pour joindre l'application ; null = proxy du serveur. */
        com.loadpilot.backend.service.http.NetworkProfile network
) {
    public TestRunPlan(UUID scenarioId, String applicationBaseUrl, ApplicationAuthSpec auth, List<StepExecutionSpec> steps,
            Map<String, String> initialVariables) {
        this(scenarioId, applicationBaseUrl, auth, steps, initialVariables, Map.of(), Map.of(),
                IntStream.rangeClosed(1, steps.size()).boxed().toList(),
                new VariableSources(List.of(), null, Map.of(), steps).captureStepByVariable(), null);
    }

    public TestRunPlan {
        if (network == null) network = com.loadpilot.backend.service.http.NetworkProfile.inherit();
    }

    public TestRunPlan(UUID scenarioId, String applicationBaseUrl, ApplicationAuthSpec auth, List<StepExecutionSpec> steps,
            Map<String, String> csvRow, Map<String, String> scenarioVariables, Map<String, String> providedVariables,
            List<Integer> stepNumbers, Map<String, Integer> captureStepByVariable) {
        this(scenarioId, applicationBaseUrl, auth, steps, csvRow, scenarioVariables, providedVariables, stepNumbers,
                captureStepByVariable, null);
    }
}
