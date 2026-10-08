package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.ExecutionStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * P1-A — vue allegee d'une Execution pour l'ecran Historique (voir
 * GET /api/executions/history) : uniquement des champs REELLEMENT
 * disponibles sur Execution/Scenario/Application (voir prompt P1-A, section
 * 8 - "ne crée que les champs réellement disponibles").
 *
 * P1-C : "triggeredByUsername" est desormais REELLEMENT disponible (voir
 * Execution.triggeredBy, ajoute en P1-B pour les Notifications) - null pour
 * toute Execution anterieure a P1-B (jamais reconstruit retroactivement) ou
 * dont l'origine n'a pu etre associee a aucun utilisateur. "throughput" est
 * calcule avec EXACTEMENT la meme formule que
 * PerformanceStatisticsService/MetricGenerationService (requetes reellement
 * executees / duree en secondes) - jamais recalcule differemment cote
 * frontend (prompt P1-C, section 4 : colonne "Throughput" de l'Historique).
 */
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
        Instant previousStartedAt
) {
    public ExecutionHistoryResponse withPrevious(Long p95, Instant startedAt) {
        return new ExecutionHistoryResponse(id, scenarioId, scenarioName, applicationId, applicationName, status, this.startedAt,
                finishedAt, duration, virtualUsers, rampUpSeconds, durationSeconds, iterations, totalSteps, successfulSteps,
                failedSteps, triggeredByUsername, throughput, cpuAvgPercent, cpuMaxPercent, memoryMaxMb, p95Ms, statusReason,
                maxErrorRatePercent, maxP95Ms, p95, startedAt);
    }
}
