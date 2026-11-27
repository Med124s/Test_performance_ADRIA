package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.enums.CsvDistribution;
import com.loadpilot.backend.enums.HttpMethod;
import com.loadpilot.backend.service.auth.ApplicationAuthSpec;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Chaine complete du mode stress (environ une minute : 40 s d'echauffement et de baseline) : la cible ralentit au-dela de
 * CAPACITY_RPS, la surveillance confirme la rupture et arrete le test, au niveau de charge ou l'anomalie a commence ;
 * une cible saine, meme lente et sous une montee rapide, n'est jamais une rupture.
 */
class StressBreakpointEndToEndTest {

    /** Au-dela de ce nombre d'arrivees dans la derniere seconde, la cible repond en SLOW_MS. */
    private static final int CAPACITY_RPS = 6;
    private static final long SLOW_MS = 800;

    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final Deque<Long> arrivals = new ArrayDeque<>();
    private static HttpServer server;
    private static String baseUrl;

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        // Cible saine : toujours rapide, sans limite de capacite.
        server.createContext("/healthy", ex -> {
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        // Cible saine a 200 ms, sans limite de capacite : il faut beaucoup de VUs pour une montee rapide.
        server.createContext("/healthy-200ms", ex -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.createContext("/", ex -> {
            boolean overloaded;
            synchronized (arrivals) {
                long now = System.nanoTime();
                arrivals.addLast(now);
                while (!arrivals.isEmpty() && arrivals.peekFirst() < now - 1_000_000_000L) arrivals.removeFirst();
                overloaded = arrivals.size() > CAPACITY_RPS;
            }
            if (overloaded) {
                try {
                    Thread.sleep(SLOW_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
        EXECUTOR.shutdown();
    }

    @Test
    void targetSlowingDownUnderLoad_isARupture_thatStopsTheTest() {
        RunningExecutionHandle handle = new RunningExecutionHandle();
        // Comme ExecutionServiceImpl : la rupture confirmee est gardee et arrete le test.
        BreakpointWatcher.Session watcher = BreakpointWatcher.start(handle, decision -> {
            handle.stressOutcome(decision);
            handle.requestStop();
        });
        List<StepExecutionSpec> steps = List.of(new StepExecutionSpec(UUID.randomUUID(), "etape", HttpMethod.GET, "/x",
                null, null, null, null, null, null, null, null, null));
        long started = System.nanoTime();
        new HttpClientExecutionEngine(5, new ObjectMapper(), EXECUTOR).execute(steps, baseUrl, ApplicationAuthSpec.none(),
                LoadTestSpec.stress(new StressRamp.Limits(200, 500, 150), 0), handle,
                new VariableSources(List.of(), CsvDistribution.PER_VIRTUAL_USER, Map.of(), steps));
        watcher.stop();
        long elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000L;

        assertThat(handle.stressOutcome()).isInstanceOfSatisfying(BreakpointWatcher.Ruptured.class, r -> {
            BreakpointDetector.Breakpoint breakpoint = r.breakpoint();
            // Reponses 100 fois plus lentes : le P95 explose et, chaque VU servant moins de requetes, le debit decroche ;
            // l'un ou l'autre confirme la rupture en premier.
            assertThat(breakpoint.criteria()).isNotEmpty()
                    .isSubsetOf(BreakpointDetector.Criterion.RESPONSE_TIME, BreakpointDetector.Criterion.THROUGHPUT);
            // La cible cede vers 7 req/s offertes (seconde 44) : anomalie des la fenetre qui la contient ou la suivante.
            assertThat(breakpoint.onsetSecond()).isBetween(40L, 55L);
            assertThat(breakpoint.offeredRps()).isBetween(3.0, 18.0);
            if (breakpoint.criteria().contains(BreakpointDetector.Criterion.RESPONSE_TIME)) {
                assertThat(breakpoint.p95Ms()).isGreaterThanOrEqualTo(SLOW_MS);
            }
        });
        assertThat(handle.stressRamp().limitReached()).as("arret par la rupture, pas par une limite").isNull();
        assertThat(elapsedSeconds).isLessThan(80);
    }

    @Test
    void healthyTarget_isNeverABreakpoint_whileTheRampRises() {
        RunningExecutionHandle handle = new RunningExecutionHandle();
        BreakpointWatcher.Session watcher = BreakpointWatcher.start(handle, decision -> {
            handle.stressOutcome(decision);
            handle.requestStop();
        });
        List<StepExecutionSpec> steps = List.of(new StepExecutionSpec(UUID.randomUUID(), "etape", HttpMethod.GET, "/healthy",
                null, null, null, null, null, null, null, null, null));
        // 40 s d'echauffement et de baseline, puis 45 s de montee (jusqu'a ~47 req/s) : arret a la duree maximale.
        new HttpClientExecutionEngine(5, new ObjectMapper(), EXECUTOR).execute(steps, baseUrl, ApplicationAuthSpec.none(),
                LoadTestSpec.stress(new StressRamp.Limits(200, 500, 85), 0), handle,
                new VariableSources(List.of(), CsvDistribution.PER_VIRTUAL_USER, Map.of(), steps));
        watcher.stop();

        assertThat(handle.stressOutcome()).as("aucune rupture sur une cible saine").isNull();
        assertThat(handle.stressRamp().limitReached()).isEqualTo(StressRamp.Limit.DURATION);
        assertThat(handle.stressRamp().peakOfferedRps()).isGreaterThan(40.0);
    }

    @Test
    void fastRampOnAHealthySlowTarget_isNeverABreakpoint_theVirtualUsersKeepUp() {
        RunningExecutionHandle handle = new RunningExecutionHandle();
        BreakpointWatcher.Session watcher = BreakpointWatcher.start(handle, decision -> {
            handle.stressOutcome(decision);
            handle.requestStop();
        });
        List<StepExecutionSpec> steps = List.of(new StepExecutionSpec(UUID.randomUUID(), "etape", HttpMethod.GET, "/healthy-200ms",
                null, null, null, null, null, null, null, null, null));
        // +20 req/s chaque seconde pendant 25 s (jusqu'a ~500 req/s, ~120 VUs) : le reservoir doit suivre, sans quoi le
        // critere de debit verrait une rupture la ou LoadPilot est seulement en retard.
        new HttpClientExecutionEngine(5, new ObjectMapper(), EXECUTOR).execute(steps, baseUrl, ApplicationAuthSpec.none(),
                LoadTestSpec.stress(new StressRamp.Limits(1000, 5000, 65, 20), 0), handle,
                new VariableSources(List.of(), CsvDistribution.PER_VIRTUAL_USER, Map.of(), steps));
        watcher.stop();

        assertThat(handle.stressOutcome()).as("aucune rupture sur une cible saine, meme a +20 req/s par seconde").isNull();
        assertThat(handle.stressRamp().limitReached()).isEqualTo(StressRamp.Limit.DURATION);
        assertThat(handle.stressRamp().peakOfferedRps()).isGreaterThan(400.0);
        // Loi de Little : ~500 req/s x 0,2 s, marge comprise ; loin de la limite de 1 000.
        assertThat(handle.stressRamp().peakVirtualUsers()).isBetween(80, 400);
    }
}
