package com.loadpilot.backend.dto.response;

import java.util.List;

/** CPU (% d'un coeur) et RAM (Mo) du processus de l'application testee. */
public record ExecutionResourcesResponse(
        String source,
        int intervalSeconds,
        Double cpuPercent,
        Double memoryMb,
        Double cpuAvgPercent,
        Double cpuMaxPercent,
        Double memoryMaxMb,
        String message,
        List<Double> cpuHistory,
        List<Double> memoryHistory
) {
}
