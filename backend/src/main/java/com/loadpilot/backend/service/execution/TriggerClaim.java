package com.loadpilot.backend.service.execution;

import java.util.UUID;

/**
 * Resultat d'une reclamation REELLE (transactionnelle, sous verrou
 * pessimiste) d'un declenchement de ScheduledExecution - voir
 * ScheduledExecutionTransactionHelper. "claimed" = false signifie que cette
 * ScheduledExecution a ete desactivee/deja reclamee/modifiee entre la
 * lecture legere (ScheduledExecutionRepository#findDueScheduleIds) et
 * l'acquisition du verrou : dans ce cas, aucun autre champ n'est
 * significatif - c'est precisement la protection anti double-declenchement
 * (jamais un simple booleen en memoire, voir sa Javadoc).
 *
 * Ne transporte que des UUID (jamais d'entite JPA geree) au-dela de la
 * transaction de reclamation - voir ExecutionService.execute pour la
 * justification complete de ce choix.
 */
public record TriggerClaim(
        boolean claimed,
        UUID scheduleId,
        UUID scenarioId,
        UUID createdByAppUserId,
        String scheduleName
) {

    public static TriggerClaim skipped() {
        return new TriggerClaim(false, null, null, null, null);
    }

    public static TriggerClaim claimed(UUID scheduleId, UUID scenarioId, UUID createdByAppUserId, String scheduleName) {
        return new TriggerClaim(true, scheduleId, scenarioId, createdByAppUserId, scheduleName);
    }
}
