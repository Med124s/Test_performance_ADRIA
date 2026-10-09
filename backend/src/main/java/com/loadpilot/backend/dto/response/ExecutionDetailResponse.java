package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.ExecutionStatus;
import java.time.Instant;
import java.util.List;

public record ExecutionDetailResponse(
        String id,
        String scenarioId,
        String scenarioName,
        Instant startedAt,
        Instant finishedAt,
        ExecutionStatus status,
        Integer virtualUsers,
        Integer rampUpSeconds,
        Integer durationSeconds,
        Integer iterations,
        Integer totalSteps,
        Integer successfulSteps,
        Integer failedSteps,
        Long duration,
        String errorMessage,
        List<ExecutionStepResultResponse> results,
        /** Detail des requetes conserve (null = execution anterieure : aucun detail). */
        com.loadpilot.backend.enums.RequestDetailMode requestDetailMode,
        /** Plafond memoire atteint : l'echantillon des requetes reussies a ete arrete en cours de route. */
        boolean detailSampleCapReached,
        long detailedFailures,
        long detailedSamples,
        /** Conservation : detail des requetes ou en-tetes / corps supprimes, option « Conserver ». */
        ResultsRetentionResponse retention
) {
}
