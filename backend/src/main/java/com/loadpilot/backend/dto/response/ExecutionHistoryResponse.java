package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.ExecutionStatus;
import java.math.BigDecimal;
import java.time.Instant;

/** Ligne de l'historique des executions. */
public record ExecutionHistoryResponse(
        String id,
        String scenarioId,
        String scenarioName,
        String applicationId,
        String applicationName,
        ExecutionStatus status,
        Instant startedAt,
        Instant finishedAt,
        Long duration,
        Integer virtualUsers,
        Integer rampUpSeconds,
        Integer durationSeconds,
        Integer iterations,
        Integer totalSteps,
        Integer successfulSteps,
        Integer failedSteps,
        String triggeredByUsername,
        BigDecimal throughput,
        /** CPU moyen / maximal du processus de l'application testee (% d'un coeur) et RAM maximale (Mo) ;
         * null sans monitoring. */
        Double cpuAvgPercent,
        Double cpuMaxPercent,
        Double memoryMaxMb,
        /** P95 (ms) du resume enregistre a la fin de l'execution (meme calcul que le rapport) ;
         * null tant qu'il n'est pas calcule (execution en cours ou anterieure, calculee en arriere-plan). */
        Long p95Ms,
        /** Raison principale du statut (« Erreurs : 2,5 % », « Annulée par … ») ; null si rien a expliquer. */
        String statusReason,
        /** Seuils de reussite copies au lancement (null = execution anterieure aux seuils). */
        Double maxErrorRatePercent,
        Long maxP95Ms,
        /** P95 et date de l'execution terminee precedente du meme scenario (liste Executions seulement). */
        Long previousP95Ms,
        Instant previousStartedAt,
        /** Option « Conserver » : resultats jamais purges. */
        boolean keepResults,
        /** Date de suppression du detail des requetes (rapport fige conserve) ; null = detail present. */
        Instant resultsPurgedAt
) {
    public ExecutionHistoryResponse withPrevious(Long p95, Instant startedAt) {
        return new ExecutionHistoryResponse(id, scenarioId, scenarioName, applicationId, applicationName, status, this.startedAt,
                finishedAt, duration, virtualUsers, rampUpSeconds, durationSeconds, iterations, totalSteps, successfulSteps,
                failedSteps, triggeredByUsername, throughput, cpuAvgPercent, cpuMaxPercent, memoryMaxMb, p95Ms, statusReason,
                maxErrorRatePercent, maxP95Ms, p95, startedAt, keepResults, resultsPurgedAt);
    }
}
