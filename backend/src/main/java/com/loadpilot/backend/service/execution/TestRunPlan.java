package com.loadpilot.backend.service.execution;

import com.loadpilot.backend.service.auth.ApplicationAuthSpec;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Tout ce dont StepTestRunner a besoin, copie dans une transaction courte (voir
 * TestRunPlanner) : le test reel se deroule ensuite hors de toute transaction.
 *
 * Memes sources que le moteur, pour l'utilisateur virtuel n°1 et sa premiere iteration :
 * premiere ligne du CSV, variables du scenario, ${authToken} ; "providedVariables" (test d'une
 * seule etape) sont traitees comme des captures des etapes precedentes (priorite maximale).
 * "stepNumbers" : numero (1..n) de chaque etape jouee dans l'ordre du scenario.
 */
public record TestRunPlan(
        UUID scenarioId,
        String applicationBaseUrl,
        ApplicationAuthSpec auth,
        List<StepExecutionSpec> steps,
        Map<String, String> csvRow,
        Map<String, String> scenarioVariables,
        Map<String, String> providedVariables,
        List<Integer> stepNumbers,
        Map<String, Integer> captureStepByVariable
) {
    /** Compatibilite : variables initiales traitees comme la ligne CSV, etapes numerotees 1..n. */
    public TestRunPlan(UUID scenarioId, String applicationBaseUrl, ApplicationAuthSpec auth, List<StepExecutionSpec> steps,
            Map<String, String> initialVariables) {
        this(scenarioId, applicationBaseUrl, auth, steps, initialVariables, Map.of(), Map.of(),
                IntStream.rangeClosed(1, steps.size()).boxed().toList(),
                new VariableSources(List.of(), null, Map.of(), steps).captureStepByVariable());
    }
}
