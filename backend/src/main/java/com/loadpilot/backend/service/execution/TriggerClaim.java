package com.loadpilot.backend.service.execution;

import java.time.Instant;
import java.util.UUID;

/**
 * Resultat d'une reclamation REELLE (transactionnelle, sous verrou
 * pessimiste) d'un declenchement de ScheduledExecution - voir
 * ScheduledExecutionTransactionHelper. "claimed" = false signifie que cette
 * ScheduledExecution a ete desactivee/deja reclamee/modifiee entre la
 * lecture legere (ScheduledExecutionRepository#findDueScheduleIds) et
 * l'acquisition du verrou, OU que l'heure prevue est manquee depuis trop
 * longtemps ("missedCount" > 0 : lancements enregistres comme manques,
 * jamais rattrapes) - c'est precisement la protection anti
 * double-declenchement (jamais un simple booleen en memoire).
 *
 * "plannedAt" = heure a laquelle le lancement etait prevu (ou l'instant du
 * "run now").
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
        String scheduleName,
        Instant plannedAt,
        int missedCount
) {

    public static TriggerClaim skipped() {
        return new TriggerClaim(false, null, null, null, null, null, 0);
    }

    public static TriggerClaim claimed(UUID scheduleId, UUID scenarioId, UUID createdByAppUserId, String scheduleName) {
        return claimed(scheduleId, scenarioId, createdByAppUserId, scheduleName, Instant.now());
    }

    public static TriggerClaim claimed(UUID scheduleId, UUID scenarioId, UUID createdByAppUserId, String scheduleName,
                                       Instant plannedAt) {
        return new TriggerClaim(true, scheduleId, scenarioId, createdByAppUserId, scheduleName, plannedAt, 0);
    }

    public static TriggerClaim missed(UUID scheduleId, UUID createdByAppUserId, String scheduleName, Instant firstPlannedAt,
                                      int missedCount) {
        return new TriggerClaim(false, scheduleId, null, createdByAppUserId, scheduleName, firstPlannedAt, missedCount);
    }
}
