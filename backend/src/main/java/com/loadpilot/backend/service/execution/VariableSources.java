package com.loadpilot.backend.service.execution;

import com.loadpilot.backend.enums.CsvDistribution;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sources de variables d'une execution : lignes CSV, distribution des lignes, variables du
 * scenario. Une instance par execution : le compteur PER_ITERATION est partage par tous les VU
 * (AtomicLong, thread-safe) ; tout le reste est en lecture seule.
 */
public final class VariableSources {

    private final List<Map<String, String>> csvRows;
    private final CsvDistribution distribution;
    private final Map<String, String> scenarioVariables;
    private final AtomicLong nextRow = new AtomicLong();
    /** Variable -> numero (1..n) de la premiere etape qui la capture (messages d'erreur). */
    private final Map<String, Integer> captureStepByVariable;

    public VariableSources(List<Map<String, String>> csvRows, CsvDistribution distribution,
            Map<String, String> scenarioVariables, List<StepExecutionSpec> steps) {
        this.csvRows = csvRows == null ? List.of() : List.copyOf(csvRows);
        this.distribution = distribution == null ? CsvDistribution.PER_VIRTUAL_USER : distribution;
        this.scenarioVariables = scenarioVariables == null ? Map.of() : Map.copyOf(scenarioVariables);
        Map<String, Integer> captures = new HashMap<>();
        if (steps != null) {
            for (int i = 0; i < steps.size(); i++) {
                StepExecutionSpec s = steps.get(i);
                if (s.captureVariableName() != null && !s.captureVariableName().isBlank()
                        && s.captureJsonPath() != null && !s.captureJsonPath().isBlank()) {
                    captures.putIfAbsent(s.captureVariableName().trim(), i + 1);
                }
            }
        }
        this.captureStepByVariable = Map.copyOf(captures);
    }

    /** Compatibilite : CSV seul, une ligne par VU, sans variables du scenario. */
    public static VariableSources of(List<Map<String, String>> csvRows) {
        return new VariableSources(csvRows, CsvDistribution.PER_VIRTUAL_USER, Map.of(), List.of());
    }

    /** Ligne CSV de l'iteration d'un VU (vide sans CSV). */
    public Map<String, String> rowFor(int virtualUser) {
        if (csvRows.isEmpty()) return Map.of();
        if (distribution == CsvDistribution.PER_ITERATION) {
            return csvRows.get((int) (nextRow.getAndIncrement() % csvRows.size()));
        }
        return csvRows.get(virtualUser % csvRows.size());
    }

    public Map<String, String> scenarioVariables() {
        return scenarioVariables;
    }

    public CsvDistribution distribution() {
        return distribution;
    }

    public Map<String, Integer> captureStepByVariable() {
        return captureStepByVariable;
    }

    /** Meme sources, numerotation des captures recalculee sur les etapes reellement jouees. */
    public VariableSources withSteps(List<StepExecutionSpec> steps) {
        return new VariableSources(csvRows, distribution, scenarioVariables, steps);
    }
}
