package com.loadpilot.backend.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.loadpilot.backend.exception.ResultSetTooLargeException;
import com.loadpilot.backend.service.execution.LiveStats;
import com.loadpilot.backend.service.execution.RunningExecutionHandle;
import com.loadpilot.backend.service.execution.RunningExecutionRegistry;
import com.loadpilot.backend.service.execution.StepOutcome;
import com.loadpilot.backend.service.impl.ResultLoadGuard.Load;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Garde-fou des lectures completes : poids par appel, refus 413 (trop volumineuse) et 409 (budget
 * occupe), aucun faux refus en usage normal, reservation et liberation dans le budget partage avec
 * les executions en cours (ResultsMemoryGuard), reservations imbriquees et concurrentes.
 */
class ResultLoadGuardTest {

    private final RunningExecutionRegistry registry = new RunningExecutionRegistry(1000, 10);
    private final ResultsMemoryBudget budget = new ResultsMemoryBudget(registry, 1000);
    private final ResultLoadGuard guard = new ResultLoadGuard(budget);

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
    void eachLoad_hasItsOwnWeight_andIsRefusedJustAboveItsLimit() {
        // Budget de 1000 « resultats » : limite = budget / poids.
        assertThat(guard.maxResults(Load.REPORT)).isEqualTo(1666);
        assertThat(guard.maxResults(Load.EXPORT)).isEqualTo(1000);
        assertThat(guard.maxResults(Load.TIMELINE)).isEqualTo(3333);
        assertThat(guard.maxResults(Load.COMPARE)).isEqualTo(1666);
        assertThat(guard.maxResults(Load.DETAIL_WITH_RESULTS)).isEqualTo(833);
        assertThat(guard.maxResults(Load.METRICS)).isEqualTo(1000);
        for (Load load : Load.values()) {
            long limit = guard.maxResults(load);
            guard.reserve(load, limit).close();
            assertThatThrownBy(() -> guard.reserve(load, limit + 1)).as(load.name())
                    .isInstanceOfSatisfying(ResultSetTooLargeException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
                        assertThat(e.getMessage()).startsWith("Exécution trop volumineuse pour ").contains(load.label)
                                .contains("limite").contains("LOADPILOT_JVM_MAX_MEMORY");
                    });
        }
        assertThat(budget.reservedForReads()).as("tout est libere").isZero();
    }

    @Test
    void normalUse_isNeverRefused_andTheReservationIsReleased() {
        try (ResultLoadGuard.Reservation r = guard.reserve(Load.REPORT, 1000)) {
            assertThat(budget.reservedForReads()).isEqualTo(600);
        }
        assertThat(budget.reservedForReads()).isZero();
        // Execution sans resultat (ou purgee, rapport fige) : rien a reserver.
        guard.reserve(Load.EXPORT, 0).close();
        assertThat(budget.reservedForReads()).isZero();
    }

    @Test
    void sharedBudget_runningTestsLeaveLessRoom_andReadsCountForTheExecutionGuard() {
        running(900);
        // 400 resultats du rapport = 240 : 900 + 240 > 1000 -> occupe, a reessayer (409).
        assertThatThrownBy(() -> guard.reserve(Load.REPORT, 400))
                .isInstanceOfSatisfying(ResultSetTooLargeException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).startsWith("Serveur momentanément occupé").contains("400 requêtes");
                });
        try (ResultLoadGuard.Reservation r = guard.reserve(Load.REPORT, 150)) { // 90 : tient encore
            assertThat(budget.reservedForReads()).isEqualTo(90);
        }

        // Dans l'autre sens : une lecture en cours compte pour le garde-fou des executions.
        RunningExecutionRegistry otherRegistry = new RunningExecutionRegistry(1000, 10);
        ResultsMemoryBudget shared = new ResultsMemoryBudget(otherRegistry, 1000);
        ExecutionServiceImpl service = mock(ExecutionServiceImpl.class);
        ResultsMemoryGuard executionGuard = new ResultsMemoryGuard(otherRegistry, service, shared);
        UUID test = UUID.randomUUID();
        otherRegistry.reserveCapacity(1);
        LiveStats stats = otherRegistry.registerReserved(test, 1).startLiveStats(List.of());
        StepOutcome ok = new StepOutcome(null, 200, 1, true, null, Instant.now());
        for (int i = 0; i < 750; i++) stats.record(-1, ok);
        executionGuard.check();
        verify(service, never()).cancelForMemoryLimit(eq(test), anyString());
        try (ResultLoadGuard.Reservation r = new ResultLoadGuard(shared).reserve(Load.REPORT, 400)) { // 240
            executionGuard.check(); // 750 + 240 < 1000
            verify(service, never()).cancelForMemoryLimit(eq(test), anyString());
            for (int i = 0; i < 20; i++) stats.record(-1, ok); // 770 + 240 >= 1000
            executionGuard.check();
            verify(service).cancelForMemoryLimit(eq(test), anyString());
        }
    }

    @Test
    void nestedReads_onTheSameThread_areCoveredByTheOuterReservation() {
        try (ResultLoadGuard.Reservation export = guard.reserve(Load.EXPORT, 500)) { // 500
            // L'export appelle le rapport puis l'analyse dans le temps : rien de plus n'est reserve.
            try (ResultLoadGuard.Reservation report = guard.reserve(Load.REPORT, 1000)) {
                assertThat(budget.reservedForReads()).isEqualTo(500);
            }
            assertThat(budget.reservedForReads()).isEqualTo(500);
        }
        assertThat(budget.reservedForReads()).isZero();
        // Hors imbrication, une nouvelle lecture reserve a nouveau.
        try (ResultLoadGuard.Reservation report = guard.reserve(Load.REPORT, 1000)) {
            assertThat(budget.reservedForReads()).isEqualTo(600);
        }
    }

    @Test
    void concurrentReads_cannotBothTakeTheLastPlace() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();
        AtomicInteger busy = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    go.await();
                    try (ResultLoadGuard.Reservation r = guard.reserve(Load.REPORT, 1000)) { // 600 chacune
                        admitted.incrementAndGet();
                        done.countDown();
                        release.await();
                    }
                } catch (ResultSetTooLargeException e) {
                    busy.incrementAndGet();
                    done.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        go.countDown();
        done.await();
        assertThat(admitted.get()).isEqualTo(1);
        assertThat(busy.get()).isEqualTo(1);
        release.countDown();
    }
}
