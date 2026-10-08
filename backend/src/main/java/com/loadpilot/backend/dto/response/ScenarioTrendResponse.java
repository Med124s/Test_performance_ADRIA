package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.ExecutionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * « Évolution des performances » d'un scenario : ses executions terminees de la periode, de la plus
 * ancienne a la plus recente. steps : etapes disponibles pour le filtre ; avec ?stepName=, chaque
 * point porte aussi le P95, le taux d'erreurs et le nombre de requetes de cette etape (null si
 * l'etape n'existe pas dans cette execution).
 */
public record ScenarioTrendResponse(String scenarioId, String scenarioName, List<StepOption> steps, String stepName, List<Point> points) {

    public record StepOption(String name, String method) {
    }

    public record Point(String executionId, Instant startedAt, ExecutionStatus status, Long p95Ms, BigDecimal errorRate,
            Double throughputRps, Integer virtualUsers, Long stepP95Ms, BigDecimal stepErrorRate, Integer stepRequests) {
    }
}
