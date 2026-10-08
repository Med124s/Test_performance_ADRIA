package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.ExecutionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Dashboard (GET /api/dashboard/overview?applicationId=&from=&to=) : tout est filtre par
 * application et par periode (executions LANCEES dans la periode). Valeurs nulles = non
 * calculables (aucune requete, aucune execution precedente...), jamais inventees.
 */
public record DashboardOverviewResponse(
        String applicationId,
        Instant from,
        Instant to,
        Kpis kpis,
        StatusCounts statusCounts,
        List<WatchItem> watchItems,
        List<ScenarioRow> latestByScenario,
        List<SlowStep> slowestSteps,
        List<ExecutionBrief> running,
        List<ExecutionBrief> recent,
        List<ScheduledBrief> upcoming
) {
    /** P95 de toutes les requetes de la periode (ms), taux d'erreurs (%), requetes, executions, en cours. */
    public record Kpis(Long p95Ms, BigDecimal errorRate, long requests, long executions, long running) {
    }

    /** thresholdsExceeded : executions allees au bout mais dont un seuil de reussite est depasse. */
    public record StatusCounts(long success, long failed, long cancelled, long running, long thresholdsExceeded) {
    }

    /**
     * Point a surveiller. kind : APPLICATION_FAILED, LAST_EXECUTION_FAILED, REGRESSION, MONITORING_DOWN ;
     * severity : danger / warning ; link : page de l'interface a ouvrir.
     */
    public record WatchItem(String kind, String severity, String title, String detail, String link) {
    }

    /**
     * Derniere execution d'un scenario dans la periode, comparee a l'execution precedente du meme
     * scenario (meme anterieure a la periode). trend : WORSE, BETTER, SAME, NONE (voir DashboardRules).
     */
    public record ScenarioRow(String executionId, String scenarioId, String scenarioName, String applicationId,
            String applicationName, Instant startedAt, ExecutionStatus status, Integer virtualUsers, Long p95Ms,
            BigDecimal errorRate, Double throughputRps, Double cpuMaxPercent, String previousExecutionId, Long previousP95Ms,
            Double p95ChangePercent, String trend) {
    }

    public record SlowStep(String stepName, String method, String scenarioName, String executionId, Long p95Ms, int requests) {
    }

    public record ExecutionBrief(String id, String scenarioName, String applicationName, ExecutionStatus status, Instant startedAt,
            Long duration) {
    }

    public record ScheduledBrief(String id, String name, String scenarioName, Instant nextRunAt) {
    }
}
