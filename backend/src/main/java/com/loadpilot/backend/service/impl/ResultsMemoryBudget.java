package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.service.execution.LiveStats;
import com.loadpilot.backend.service.execution.RunningExecutionHandle;
import com.loadpilot.backend.service.execution.RunningExecutionRegistry;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Budget memoire des resultats, en "resultats" (~2,5 Ko chacun), partage par les executions en cours et les lectures de resultats. */
@Component
public class ResultsMemoryBudget {

    /** Memoire comptee par resultat. */
    static final long BYTES_PER_RESULT = 2560;

    private final RunningExecutionRegistry registry;
    private final long maxResults;
    private long reservedForReads;

    public ResultsMemoryBudget(RunningExecutionRegistry registry,
            @Value("${app.execution.max-results-in-memory:0}") long configured) {
        this.registry = registry;
        this.maxResults = configured > 0 ? configured : defaultLimit(Runtime.getRuntime().maxMemory());
    }

    /** Limite par defaut pour une memoire JVM maximale donnee (octets). */
    static long defaultLimit(long maxMemoryBytes) {
        return Math.max(10_000L, maxMemoryBytes / BYTES_PER_RESULT);
    }

    public long maxResults() {
        return maxResults;
    }

    /** Resultats en memoire des executions suivies. */
    public long executionsTotal() {
        long total = 0;
        for (UUID id : registry.activeExecutionIds()) {
            total += inMemory(registry.get(id));
        }
        return total;
    }

    /** Resultats qu'une execution tient en memoire, en unites du budget. */
    public static long inMemory(RunningExecutionHandle handle) {
        if (handle == null) return 0;
        com.loadpilot.backend.service.execution.ResultStream stream = handle.resultStream();
        if (stream != null) {
            return stream.inMemory() + (stream.summary().approxBytes() + BYTES_PER_RESULT - 1) / BYTES_PER_RESULT;
        }
        LiveStats stats = handle.liveStats();
        return stats == null ? 0 : stats.totalRequests();
    }

    public synchronized long reservedForReads() {
        return reservedForReads;
    }

    /** Resultat d'une demande de reservation. */
    public enum Admission { ADMITTED, TOO_LARGE, BUSY }

    /** Reserve "units" pour une lecture : TOO_LARGE si elle depasse le budget, BUSY si elle ne tient pas dans ce qui reste. */
    public synchronized Admission tryReserve(long units) {
        if (units <= 0) return Admission.ADMITTED;
        if (units > maxResults) return Admission.TOO_LARGE;
        if (executionsTotal() + reservedForReads + units > maxResults) return Admission.BUSY;
        reservedForReads += units;
        return Admission.ADMITTED;
    }

    public synchronized void release(long units) {
        if (units > 0) reservedForReads = Math.max(0, reservedForReads - units);
    }
}
