package com.loadpilot.backend.dto.request;

import com.loadpilot.backend.enums.StopMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Passage produit reel (2026-10-01) — les 7 champs de charge sont
 * desormais des SURCHARGES REELLEMENT propres a CETTE execution : null
 * (absent) = utilise la valeur actuellement enregistree sur le Scenario
 * (comportement historique inchange, compatibilite totale avec l'ancien
 * contrat {scenarioId} seul) ; non-null = utilise cette valeur UNIQUEMENT
 * pour cette Execution (copiee sur Execution.*, voir
 * ExecutionTransactionHelper.prepareAndStart) SANS jamais modifier le
 * Scenario en base - contrairement au comportement precedent ou le
 * frontend appelait PUT /api/scenarios avant de lancer.
 */
public record ExecutionRequest(
        @NotNull(message = "L'identifiant du scenario est obligatoire.")
        UUID scenarioId,

        @Min(value = 1, message = "Le nombre d'utilisateurs virtuels doit etre au moins 1.")
        @Max(value = 10000, message = "Le nombre d'utilisateurs virtuels ne doit pas depasser 10000.")
        Integer virtualUsers,

        @Min(value = 0, message = "Le ramp-up ne peut pas etre negatif.")
        Integer rampUpSeconds,

        @Min(value = 1, message = "La duree doit etre d'au moins 1 seconde si fournie.")
        Integer durationSeconds,

        @Min(value = 1, message = "Le nombre d'iterations doit etre d'au moins 1 si fourni.")
        Integer iterations,

        @Min(value = 0, message = "Le think time ne peut pas etre negatif.")
        Integer thinkTimeMs,

        @Min(value = 1, message = "Le debit cible (RPS) doit etre d'au moins 1 si fourni.")
        Integer targetRps,

        StopMode stopMode
) {
    /** Compatibilite : ancienne forme {scenarioId} seul - aucune surcharge,
     * les valeurs actuellement enregistrees sur le Scenario sont utilisees
     * telles quelles (comportement historique inchange). */
    public ExecutionRequest(UUID scenarioId) {
        this(scenarioId, null, null, null, null, null, null, null);
    }
}
