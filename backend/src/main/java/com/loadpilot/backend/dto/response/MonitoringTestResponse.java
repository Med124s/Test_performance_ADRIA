package com.loadpilot.backend.dto.response;

/** Resultat du test du monitoring : cpuPercent en % d'un coeur, memoryMb en Mo ; null si indisponible. */
public record MonitoringTestResponse(
        boolean success,
        String message,
        Double cpuPercent,
        Double memoryMb,
        String memoryMetric
) {
}
