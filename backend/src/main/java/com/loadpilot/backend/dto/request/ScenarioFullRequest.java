package com.loadpilot.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Modification complete d'un scenario : informations, CSV, variables, charge et liste ordonnee des etapes. Etape avec "id" modifiee, sans "id" creee, absente supprimee. */
public record ScenarioFullRequest(
        @NotNull(message = "Les informations du scénario sont obligatoires.")
        @Valid
        ScenarioRequest scenario,

        @NotNull(message = "La liste des étapes est obligatoire.")
        @Size(max = 200, message = "200 étapes au maximum.")
        List<@Valid StepPayload> steps
) {
    /** "step.scenarioId" et "step.order" sont ignores (fixes par le scenario et la position). */
    public record StepPayload(UUID id, @NotNull(message = "Étape incomplète.") @Valid StepRequest step) {
    }
}
