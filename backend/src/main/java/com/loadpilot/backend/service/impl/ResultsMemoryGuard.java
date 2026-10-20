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

/**
 * Garde-fou memoire : les resultats d'une execution (StepOutcome) sont ecrits en base pendant le test
 * (ResultStream) ; seuls ceux encore dans le tampon d'ecriture, et les totaux compacts, restent en
 * memoire (voir ResultsMemoryBudget.inMemory). Le garde-fou arrete donc un test quand ce tampon grossit
 * sans se vider (base trop lente ou indisponible) ; un test qui ecrit normalement n'est plus limite par
 * sa taille. Les lectures apres le test (resume, metriques, rapport) reservent leur part via
 * ResultLoadGuard. Sans flux d'ecriture (moteur appele directement), tous les resultats comptent,
 * comme avant.
 *
 * Budget GLOBAL : somme des resultats en memoire de toutes les executions suivies par ce processus
 * (jusqu'a LOADPILOT_MAX_CONCURRENT_EXECUTIONS en meme temps, qui partagent la meme memoire), y
 * compris celles deja terminees qui enregistrent encore leurs resultats. Budget atteint : l'execution
 * EN COURS qui en tient le plus est arretee par le chemin de l'annulation (motif distinct), ses
 * resultats deja obtenus sont finalises normalement ; s'il est encore depasse a la verification
 * suivante, la suivante l'est aussi.
 *
 * Valeur par defaut calculee sur la memoire maximale de la JVM (-Xmx) : le pic n'est pas pendant le
 * test (~0,25 Ko par resultat) mais juste apres (finalisation puis metriques, ~1,3 Ko par resultat,
 * plus l'ouverture du rapport) - d'ou ~2,5 Ko par resultat : ~1,6 M avec 4 Go, ~3,3 M avec 8 Go.
 *
 * Cout : aucun sur le chemin des requetes (compteur LongAdder deja tenu par le suivi en direct), une
 * lecture des compteurs toutes les 500 ms dans un thread virtuel dedie (jamais le planificateur
 * partage, qu'une tache longue pourrait retarder).
 */
@Component
public class ResultsMemoryGuard {

    private static final Logger log = LoggerFactory.getLogger(ResultsMemoryGuard.class);

    static final long CHECK_INTERVAL_MS = 500;
    /** Debut du message d'arret (reconnu par ExecutionStatusReason). */
    public static final String REASON_PREFIX = com.loadpilot.backend.service.execution.ExecutionStatusReason.MEMORY_LIMIT_PREFIX;

    private final RunningExecutionRegistry registry;
    private final ExecutionServiceImpl executionService;
    /** Budget partage avec les lectures de resultats (voir ResultsMemoryBudget, ResultLoadGuard). */
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
                    // Le garde-fou ne s'arrete jamais sur une erreur ponctuelle.
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

    /** Une verification : arrete au plus UNE execution (la plus grosse encore en cours) si le budget est atteint. */
    void check() {
        long maxResults = budget.maxResults();
        // Lectures en cours (rapport, exports...) comprises : elles occupent la meme memoire.
        long total = budget.reservedForReads();
        UUID largest = null;
        long largestCount = -1;
        for (UUID id : registry.activeExecutionIds()) {
            RunningExecutionHandle handle = registry.get(id);
            if (handle == null || handle.liveStats() == null) continue;
            // Resultats encore en memoire seulement : ceux deja ecrits en base (ResultStream) ne comptent plus.
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
