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
        BigDecimal throughput
) {
}
