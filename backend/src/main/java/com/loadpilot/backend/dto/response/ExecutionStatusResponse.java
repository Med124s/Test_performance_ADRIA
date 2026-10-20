package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.service.execution.LiveStats;
import java.time.Instant;

/**
 * Vue allegee d'une Execution (P0-A), pensee pour du polling frequent
 * pendant un test de charge en cours - jamais le detail complet (pas de
 * liste de resultats par etape, potentiellement tres volumineuse avec
 * plusieurs utilisateurs virtuels).
 *
 * Pendant RUNNING, les totaux et "live" viennent des compteurs EN MEMOIRE du moteur (aucune
 * lecture des resultats en base) ; une fois terminee, les totaux viennent de l'Execution
 * finalisee et "live" vaut null (le rapport donne alors les statistiques completes).
 *
 * "progressPercent" n'est JAMAIS invente : temps ecoule / duree, sinon iterations faites /
 * prevues, plafonne a 99 % avant la finalisation reelle.
 */
public record ExecutionStatusResponse(
        String id,
        ExecutionStatus status,
        Instant startedAt,
        Instant finishedAt,
        Long duration,
        Integer virtualUsers,
        Integer totalRequests,
        Integer successfulRequests,
        Integer failedRequests,
        Integer progressPercent,
        String scenarioId,
        String scenarioName,
        Integer rampUpSeconds,
        Integer durationSeconds,
        Integer iterations,
        /** Iterations prevues au total (VU x iterations, ou VU sans duree ni iterations) ; null avec une duree seule. */
        Long plannedIterations,
        String errorMessage,
        LiveStats.Snapshot live,
        /** Debut de la pause en cours (statut PAUSED), sinon null. */
        Instant pausedAt,
        /** Temps total passe en pause (ms), pause en cours comprise. */
        long pausedDurationMs,
        /** Temps de travail reel (ms) : ecoule depuis le demarrage, pauses exclues ; null tant que non demarre. */
        Long activeElapsedMs,
        /** CPU / RAM de l'application testee ; null si aucun monitoring n'est configure. */
        ExecutionResourcesResponse resources,
        /** LoadPilot lui-meme pendant le test (CPU, memoire, threads, saturation) ; null hors execution en cours. */
        InjectorStatusResponse injector
) {
}
