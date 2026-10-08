package com.loadpilot.backend.dto.response;

/**
 * Resultat de « Tester le monitoring » : deux lectures a une seconde d'intervalle (le CPU
 * Prometheus est un compteur cumule : il faut deux lectures pour un pourcentage).
 * cpuPercent en % d'un coeur ; memoryMb en Mo ; null si la valeur n'a pas pu etre lue.
 */
public record MonitoringTestResponse(
        boolean success,
        String message,
        Double cpuPercent,
        Double memoryMb,
        String memoryMetric
) {
}
