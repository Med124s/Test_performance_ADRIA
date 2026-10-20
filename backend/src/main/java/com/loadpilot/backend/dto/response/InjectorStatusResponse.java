package com.loadpilot.backend.dto.response;

/**
 * LoadPilot lui-meme (l'injecteur) pendant le test : CPU du processus et de la machine (% de la
 * machine entiere, null si indisponible), tas de la JVM, threads virtuels actifs (un par utilisateur
 * virtuel actif) et threads de plateforme. overloaded : le CPU de LoadPilot a atteint le seuil au
 * moins une fois pendant ce test ; warning : message a afficher (null sinon).
 */
public record InjectorStatusResponse(
        Double cpuPercent,
        Double cpuMaxPercent,
        Double machineCpuPercent,
        long heapUsedMb,
        long heapMaxMb,
        int activeVirtualThreads,
        int platformThreads,
        double cpuWarningPercent,
        boolean overloaded,
        String warning
) {
}
