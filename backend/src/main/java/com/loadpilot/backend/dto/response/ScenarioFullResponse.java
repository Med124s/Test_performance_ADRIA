package com.loadpilot.backend.dto.response;

import java.util.List;

/** Scenario et ses etapes (ordre d'execution) apres une modification complete. */
public record ScenarioFullResponse(ScenarioResponse scenario, List<StepResponse> steps) {
}
