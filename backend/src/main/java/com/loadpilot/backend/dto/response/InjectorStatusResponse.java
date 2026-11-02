package com.loadpilot.backend.dto.response;

/** LoadPilot pendant le test : CPU, memoire de la JVM, threads virtuels et threads de plateforme. */
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
