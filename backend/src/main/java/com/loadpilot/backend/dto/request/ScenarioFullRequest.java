package com.loadpilot.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Modification complete d'un scenario en une seule operation (PUT /api/scenarios/{id}/full) :
 * informations, CSV, variables, charge ET liste ordonnee des etapes. Une etape avec "id" est
 * modifiee, sans "id" creee ; les etapes absentes de la liste sont supprimees ; l'ordre de la
 * liste devient l'ordre d'execution (1..n). Sert aussi de brouillon pour le test detaille d'une
 * version non enregistree (POST /api/scenarios/{id}/test-run avec corps).
 */
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
