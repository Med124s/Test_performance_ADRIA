package com.loadpilot.backend.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.entity.ExecutionStepResult;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.ExecutionStepResultRepository;
import com.loadpilot.backend.service.report.ExecutionStatistics;
import com.loadpilot.backend.service.report.PerformanceStatisticsService;
import com.loadpilot.backend.service.report.StepReportEntry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resume d'une execution TERMINEE (P95 global, debit, P95 par etape) calcule une seule fois puis
 * enregistre sur l'execution : c'est le cache du Dashboard (jamais recalcule pour une execution
 * terminee). Exactement le calcul du rapport (PerformanceStatisticsService, debit hors pauses) :
 * le Dashboard et le rapport affichent toujours les memes valeurs.
 *
 * Calcule a la fin de chaque execution (ExecutionServiceImpl.runAsync) ; pour les executions
 * anterieures, a leur premiere consultation par le Dashboard (ensureComputed).
 */
@Service
@RequiredArgsConstructor
public class ExecutionStatsService {

    private final ExecutionRepository executionRepository;
    private final ExecutionStepResultRepository resultRepository;
    private final PerformanceStatisticsService statisticsService;
    private final ObjectMapper objectMapper;
    private final jakarta.persistence.EntityManager entityManager;

    /** P95 d'une etape dans le resume enregistre. */
    public record StepStat(String name, String method, Long p95, int total, Integer failed) {
    }

    /** Calcule et enregistre le resume (transaction propre : un echec n'affecte jamais l'appelant). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void compute(UUID executionId) {
        Execution execution = executionRepository.findById(executionId).orElse(null);
        if (execution == null || execution.getStatus() == null || execution.getStatus().isActive()
                || execution.getResultsPurgedAt() != null) return;
        List<ExecutionStepResult> results = resultRepository.findByExecutionIdOrderByTimestampAsc(executionId);
        long pausedMs = ExecutionServiceImpl.pausedDurationMs(execution, Instant.now());
        Long activeDurationMs = execution.getDuration() != null ? Math.max(0L, execution.getDuration() - pausedMs) : null;
        ExecutionStatistics stats = statisticsService.computeOverallStatistics(results, activeDurationMs);
        List<StepStat> steps = new ArrayList<>();
        for (StepReportEntry s : statisticsService.computeStepBreakdown(results)) {
            steps.add(new StepStat(s.stepName(), s.method() == null ? null : s.method().name(), s.p95(), s.total(), s.failed()));
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(steps);
            if (json.length() > 50000) json = null;
        } catch (Exception e) {
            json = null;
        }
        // Mise a jour CIBLEE des seules colonnes du resume : jamais l'execution entiere (une ecriture
        // concurrente de la chronologie ou des mesures CPU / RAM ne peut pas etre ecrasee).
        entityManager.detach(execution);
        entityManager.createQuery("update Execution e set e.p95Ms = :p95, e.throughputRps = :rps, e.stepStatsJson = :steps, "
                        + "e.statsComputedAt = :now where e.id = :id")
                .setParameter("p95", stats.p95())
                .setParameter("rps", stats.throughput() == null ? null : stats.throughput().doubleValue())
                .setParameter("steps", json)
                .setParameter("now", Instant.now())
                .setParameter("id", executionId)
                .executeUpdate();
    }

    /** Vrai si le resume doit encore etre calcule (execution terminee, jamais resumee). */
    public static boolean needsComputation(Execution e) {
        if (e.getStatus() == null || e.getStatus().isActive() || e.getResultsPurgedAt() != null) return false;
        // Resume absent, ou anterieur a l'ajout des echecs par etape : recalcule une fois.
        String json = e.getStepStatsJson();
        return e.getStatsComputedAt() == null || (json != null && json.contains("\"name\"") && !json.contains("\"failed\""));
    }

    /** Lecture du P95 par etape enregistre (liste vide si absent ou illisible). */
    public List<StepStat> stepStats(Execution execution) {
        if (execution.getStepStatsJson() == null) return List.of();
        try {
            return List.of(objectMapper.readValue(execution.getStepStatsJson(), StepStat[].class));
        } catch (Exception e) {
            return List.of();
        }
    }
}
