package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.service.execution.LiveStats;
import com.loadpilot.backend.service.execution.RunningExecutionHandle;
import com.loadpilot.backend.service.execution.RunningExecutionRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Garde-fou memoire : arrete l'execution la plus grosse si les resultats en memoire depassent le budget. */
@Component
public class ResultsMemoryGuard {

    private static final Logger log = LoggerFactory.getLogger(ResultsMemoryGuard.class);

    static final long CHECK_INTERVAL_MS = 500;
    /** Debut du message d'arret (reconnu par ExecutionStatusReason). */
    public static final String REASON_PREFIX = com.loadpilot.backend.service.execution.ExecutionStatusReason.MEMORY_LIMIT_PREFIX;

    private final RunningExecutionRegistry registry;
    private final ExecutionServiceImpl executionService;
    /** Budget partage avec les lectures de resultats. */
    private final ResultsMemoryBudget budget;
    private volatile Thread thread;

    public ResultsMemoryGuard(RunningExecutionRegistry registry, ExecutionServiceImpl executionService, ResultsMemoryBudget budget) {
        this.registry = registry;
        this.executionService = executionService;
        this.budget = budget;
    }

    /** Limite par defaut pour une memoire JVM maximale donnee (octets). */
    static long defaultLimit(long maxMemoryBytes) {
        return ResultsMemoryBudget.defaultLimit(maxMemoryBytes);
    }

    public long maxResults() {
        return budget.maxResults();
    }

    @PostConstruct
    void start() {
        log.info("Limite de securite : {} resultats en memoire au plus, toutes executions et lectures confondues", maxResults());
        thread = Thread.ofVirtual().name("results-memory-guard").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(CHECK_INTERVAL_MS);
                    check();
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    log.warn("Verification des resultats en memoire echouee : {}", e.getClass().getSimpleName());
                }
            }
        });
    }

    @PreDestroy
    void stop() {
        Thread t = thread;
        if (t != null) t.interrupt();
    }

    /** Une verification : arrete au plus une execution si le budget est atteint. */
    void check() {
        long maxResults = budget.maxResults();
        // Lectures en cours comprises.
        long total = budget.reservedForReads();
        UUID largest = null;
        long largestCount = -1;
        for (UUID id : registry.activeExecutionIds()) {
            RunningExecutionHandle handle = registry.get(id);
            if (handle == null || handle.liveStats() == null) continue;
            // Seuls les resultats encore en memoire comptent.
            long count = ResultsMemoryBudget.inMemory(handle);
            total += count;
            if (!handle.isCancelled() && count > largestCount) {
                largest = id;
                largestCount = count;
            }
        }
        if (total < maxResults || largest == null) return;
        log.warn("Limite de securite atteinte : {} resultats en memoire (limite {}), arret de l'execution {}",
                total, maxResults, largest);
        executionService.cancelForMemoryLimit(largest, reason(maxResults, total, largestCount));
    }

    static String reason(long limit, long total, long ofThisExecution) {
        NumberFormat f = NumberFormat.getIntegerInstance(Locale.FRANCE);
        return REASON_PREFIX + " (limite de sécurité atteinte : " + f.format(total) + " résultats en mémoire sur ce serveur, "
                + "limite " + f.format(limit) + ", dont " + f.format(ofThisExecution) + " pour cette exécution). "
                + "Les résultats déjà obtenus sont conservés.";
    }
}
