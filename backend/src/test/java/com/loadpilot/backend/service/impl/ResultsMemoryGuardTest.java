package com.loadpilot.backend.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.loadpilot.backend.service.execution.ExecutionStatusReason;
import com.loadpilot.backend.service.execution.LiveStats;
import com.loadpilot.backend.service.execution.RunningExecutionHandle;
import com.loadpilot.backend.service.execution.RunningExecutionRegistry;
import com.loadpilot.backend.service.execution.StepOutcome;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.enums.ExecutionStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Garde-fou memoire : limite par defaut, budget global, choix de l'execution arretee, motif distinct. */
class ResultsMemoryGuardTest {

    private final RunningExecutionRegistry registry = new RunningExecutionRegistry(1000, 10);
    private final ExecutionServiceImpl service = mock(ExecutionServiceImpl.class);

    private UUID running(long results) {
        UUID id = UUID.randomUUID();
        registry.reserveCapacity(1);
        RunningExecutionHandle handle = registry.registerReserved(id, 1);
        LiveStats stats = handle.startLiveStats(List.of());
        StepOutcome ok = new StepOutcome(null, 200, 1, true, null, Instant.now());
        for (long i = 0; i < results; i++) stats.record(-1, ok);
        return id;
    }

    @Test
    void defaultLimit_followsTheJvmMemory() {
        long gib = 1024L * 1024 * 1024;
        assertThat(ResultsMemoryGuard.defaultLimit(4 * gib)).isEqualTo(1_677_721L);
        assertThat(ResultsMemoryGuard.defaultLimit(8 * gib)).isEqualTo(3_355_443L);
        assertThat(ResultsMemoryGuard.defaultLimit(1024)).as("plancher").isEqualTo(10_000L);
        assertThat(new ResultsMemoryGuard(registry, service, new ResultsMemoryBudget(registry, 0)).maxResults())
                .isEqualTo(ResultsMemoryGuard.defaultLimit(Runtime.getRuntime().maxMemory()));
        assertThat(new ResultsMemoryGuard(registry, service, new ResultsMemoryBudget(registry, 500)).maxResults()).as("LOADPILOT_MAX_RESULTS_IN_MEMORY").isEqualTo(500);
    }

    @Test
    void underTheBudget_nothingIsStopped() {
        running(300);
        running(199);
        new ResultsMemoryGuard(registry, service, new ResultsMemoryBudget(registry, 500)).check();
        verify(service, never()).cancelForMemoryLimit(any(), anyString());
    }

    @Test
    void budgetIsGlobal_theLargestRunningExecutionIsStopped() {
        // Aucune execution ne depasse seule 500, mais ensemble oui : la plus grosse est arretee.
        running(200);
        UUID largest = running(260);
        running(100);
        new ResultsMemoryGuard(registry, service, new ResultsMemoryBudget(registry, 500)).check();
        verify(service).cancelForMemoryLimit(eq(largest), anyString());
    }

    @Test
    void anExecutionAlreadyStopped_stillCounts_butTheNextRunningOneIsChosen() {
        // Arretee, mais ses resultats sont encore en memoire (finalisation, metriques).
        UUID finalizing = running(400);
        registry.get(finalizing).requestCancellation("deja arretee");
        UUID other = running(150);
        new ResultsMemoryGuard(registry, service, new ResultsMemoryBudget(registry, 500)).check();
        verify(service, never()).cancelForMemoryLimit(eq(finalizing), anyString());
        verify(service).cancelForMemoryLimit(eq(other), anyString());

        // Une fois sa tache terminee (desinscrite), sa memoire est liberee : plus rien a arreter.
        registry.unregister(finalizing);
        registry.get(other).requestCancellation("arretee");
        ExecutionServiceImpl fresh = mock(ExecutionServiceImpl.class);
        running(100);
        new ResultsMemoryGuard(registry, fresh, new ResultsMemoryBudget(registry, 500)).check();
        verify(fresh, never()).cancelForMemoryLimit(any(), anyString());
    }

    /** Execution qui ecrit ses resultats au fil du test : "results" requetes passees par le flux. */
    private UUID streaming(long results, com.loadpilot.backend.service.execution.ResultStream.Writer writer) {
        UUID id = running(0);
        RunningExecutionHandle handle = registry.get(id);
        com.loadpilot.backend.service.execution.ResultStream stream =
                new com.loadpilot.backend.service.execution.ResultStream(id.toString(), writer);
        handle.resultStream(stream);
        StepOutcome ok = new StepOutcome(null, 200, 1, true, null, Instant.now());
        for (long i = 0; i < results; i++) {
            handle.liveStats().record(-1, ok);
            stream.accept(ok);
        }
        return id;
    }

    private static void awaitInMemory(RunningExecutionHandle handle, long atMost) throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (ResultsMemoryBudget.inMemory(handle) > atMost && System.nanoTime() < deadline) Thread.sleep(10);
    }

    @Test
    void resultsAlreadyWrittenToTheDatabase_noLongerCount_onlyTheWriteBufferDoes() throws Exception {
        // 5 000 requetes deja ecrites en base : bien au-dela de 500, mais plus en memoire.
        UUID written = streaming(5_000, batch -> { });
        RunningExecutionHandle handle = registry.get(written);
        awaitInMemory(handle, 50);
        assertThat(ResultsMemoryBudget.inMemory(handle)).as("tampon vide + totaux compacts : moins de 1 % des requetes").isLessThanOrEqualTo(50);
        new ResultsMemoryGuard(registry, service, new ResultsMemoryBudget(registry, 500)).check();
        verify(service, never()).cancelForMemoryLimit(any(), anyString());
        handle.resultStream().close();
    }

    @Test
    void aWriteBufferThatKeepsGrowing_isStopped() {
        // Base bloquee : les resultats s'accumulent dans le tampon d'ecriture.
        java.util.concurrent.CountDownLatch blocked = new java.util.concurrent.CountDownLatch(1);
        UUID stuck = streaming(800, batch -> {
            try {
                blocked.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(ResultsMemoryBudget.inMemory(registry.get(stuck))).isGreaterThanOrEqualTo(800);
        new ResultsMemoryGuard(registry, service, new ResultsMemoryBudget(registry, 500)).check();
        verify(service).cancelForMemoryLimit(eq(stuck), anyString());
        blocked.countDown();
        registry.get(stuck).resultStream().close();
    }

    @Test
    void reason_isDistinctFromTheErrorBrakeAndAManualCancel() {
        String reason = ResultsMemoryGuard.reason(1_600_000, 1_600_123, 1_200_000);
        assertThat(reason).startsWith("Arrêt automatique : trop de résultats en mémoire (limite de sécurité atteinte")
                .contains("1 600 123").contains("Les résultats déjà obtenus sont conservés.");
        Execution stopped = Execution.builder().status(ExecutionStatus.CANCELLED).errorMessage(reason).build();
        assertThat(ExecutionStatusReason.of(stopped)).isEqualTo("Arrêt automatique : limite mémoire");
        stopped.setErrorMessage("Arrêt automatique : 30 % d'erreurs sur les 30 dernières secondes.");
        assertThat(ExecutionStatusReason.of(stopped)).isEqualTo("Arrêt automatique : trop d’erreurs");
    }
}
