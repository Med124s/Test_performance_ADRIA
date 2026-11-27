package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.enums.StressSeriesStatus;
import com.loadpilot.backend.service.execution.StressSeriesSummary;
import java.time.Instant;
import java.util.List;

/** Serie d'essais de stress : essais, etat (pause comprise) et point de rupture consolide. */
public record StressSeriesResponse(
        String id,
        String scenarioId,
        String scenarioName,
        String applicationName,
        int requestedRuns,
        StressSeriesStatus status,
        /** INTERRUPTED / CANCELLED : pourquoi la serie s'est arretee avant la fin. */
        String stopReason,
        String triggeredByUsername,
        Instant startedAt,
        Instant finishedAt,
        /** Pause entre deux essais : lancement prevu de l'essai suivant ; null sinon. */
        Instant nextRunAt,
        int pauseSeconds,
        /** Essai en attente ou en cours ; null pendant une pause ou une fois la serie terminee. */
        String currentExecutionId,
        List<Run> runs,
        /** Point de rupture consolide sur les essais termines. */
        StressSeriesSummary summary
) {

    /** Un essai de la serie. */
    public record Run(int index, String executionId, ExecutionStatus status, Instant startedAt, Long duration,
            StressResultResponse stress) {
    }
}
