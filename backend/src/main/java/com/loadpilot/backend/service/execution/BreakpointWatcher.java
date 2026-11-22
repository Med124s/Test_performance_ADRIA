package com.loadpilot.backend.service.execution;

import com.loadpilot.backend.service.monitoring.InjectorTrack;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mode stress : surveillance pendant le test. Baseline a la fin de [echauffement, echauffement + baseline[, puis chaque
 * fenetre terminee passe au BreakpointDetector ; la saturation de LoadPilot rend le test non concluant, sauf anomalie
 * de la cible commencee avant elle.
 */
public final class BreakpointWatcher {

    private static final Logger log = LoggerFactory.getLogger(BreakpointWatcher.class);

    static final long CHECK_INTERVAL_MS = 500;
    /** Debut du motif d'un test non concluant (reconnu par ExecutionStatusReason). */
    public static final String INCONCLUSIVE_PREFIX = "Non concluant — LoadPilot a atteint sa propre limite technique avant la cible";

    /** Decision de la surveillance. */
    public sealed interface Outcome permits Ruptured, BaselineUnusable, Inconclusive {
    }

    /** Point de rupture confirme. */
    public record Ruptured(BreakpointDetector.Breakpoint breakpoint) implements Outcome {
    }

    /** Baseline sans assez de reponses : le test n'a pas pu se derouler. */
    public record BaselineUnusable(String reason) implements Outcome {
    }

    /** LoadPilot sature avant la cible. */
    public record Inconclusive(String reason) implements Outcome {
    }

    /** Surveillance en cours ; stop() est idempotent. */
    public interface Session {
        void stop();
    }

    private final RunningExecutionHandle handle;
    private BreakpointDetector detector;
    private long nextWindowStart = StressSettings.WARMUP_SECONDS + StressSettings.BASELINE_SECONDS;

    BreakpointWatcher(RunningExecutionHandle handle) {
        this.handle = handle;
    }

    /** Un passage : decision, ou null tant que rien n'est decide (ou que le moteur n'a pas demarre). */
    Outcome step() {
        LiveStats live = handle.liveStats();
        StressRamp ramp = handle.stressRamp();
        if (live == null || ramp == null) return null;
        long now = live.elapsedSeconds();
        long baselineEnd = StressSettings.WARMUP_SECONDS + StressSettings.BASELINE_SECONDS;
        if (detector == null && now >= baselineEnd) {
            LiveStats.Window window = live.window(StressSettings.WARMUP_SECONDS, baselineEnd);
            BreakpointDetector.Baseline baseline = BreakpointDetector.baselineOf(window);
            if (baseline == null) return new BaselineUnusable(BreakpointDetector.baselineUnusableMessage(window));
            detector = new BreakpointDetector(baseline);
            log.info("Test de stress : baseline P95 {} ms sur {} réponses.", baseline.p95Ms(), baseline.responses());
        }
        while (detector != null && nextWindowStart + StressSettings.WINDOW_SECONDS <= now) {
            BreakpointDetector.WindowView view = view(live, ramp, nextWindowStart);
            nextWindowStart += StressSettings.WINDOW_SECONDS;
            BreakpointDetector.Breakpoint breakpoint = detector.onWindow(view);
            if (breakpoint != null) return new Ruptured(breakpoint);
        }
        InjectorTrack injector = handle.injector();
        if (injector != null && injector.consecutiveOverloadedSamples() >= StressSettings.INJECTOR_SATURATED_SAMPLES) {
            long saturationStart = Math.floorDiv(injector.overloadedSinceNanos() - live.startedNanos(), TimeUnit.SECONDS.toNanos(1));
            long openAnomalyEnd = detector == null ? -1 : detector.openAnomalyEndSecond();
            if (BreakpointDetector.inconclusive(saturationStart, openAnomalyEnd)) {
                return new Inconclusive(inconclusiveMessage(injector, Math.max(0, saturationStart)));
            }
        }
        return null;
    }

    /** Fenetre [start, start + WINDOW_SECONDS[ avec le debit offert moyen et l'etat actuel de la montee. */
    static BreakpointDetector.WindowView view(LiveStats live, StressRamp ramp, long start) {
        double offered = 0;
        int noted = 0;
        for (long s = start; s < start + StressSettings.WINDOW_SECONDS; s++) {
            LiveStats.LoadLevel level = live.loadLevelAt(s);
            if (level != null) {
                offered += level.offeredRps();
                noted++;
            }
        }
        return new BreakpointDetector.WindowView(start, live.window(start, start + StressSettings.WINDOW_SECONDS),
                noted == 0 ? ramp.offeredRps() : offered / noted, ramp.rising(), ramp.poolAtLimit(), live.loadLevelAt(start));
    }

    static String inconclusiveMessage(InjectorTrack injector, long saturationStartSecond) {
        InjectorTrack.Sample latest = injector.latest();
        Double cpu = latest != null ? latest.processCpuPercent() : null;
        return INCONCLUSIVE_PREFIX + " : CPU de LoadPilot " + (cpu != null ? "à " + ExecutionVerdict.format(cpu) + " % " : "")
                + "(seuil " + ExecutionVerdict.format(injector.cpuWarningPercent()) + " %) sur "
                + injector.consecutiveOverloadedSamples() + " lectures consécutives, depuis " + BreakpointDetector.elapsed(saturationStartSecond)
                + " du début, avant tout signe de rupture de l'application.";
    }

    /** Surveille "handle" jusqu'a une decision (transmise une fois a onOutcome), l'arret, la fin ou l'annulation. */
    public static Session start(RunningExecutionHandle handle, Consumer<Outcome> onOutcome) {
        BreakpointWatcher watcher = new BreakpointWatcher(handle);
        Thread thread = Thread.ofVirtual().name("breakpoint-watcher").start(() -> {
            try {
                while (!handle.isCancelled() && !handle.isStopRequested()) {
                    Thread.sleep(CHECK_INTERVAL_MS);
                    Outcome outcome = watcher.step();
                    if (outcome != null && !handle.isCancelled() && !handle.isStopRequested()) {
                        onOutcome.accept(outcome);
                        return;
                    }
                }
            } catch (InterruptedException e) {
                // stop() : fin de l'execution.
            } catch (RuntimeException e) {
                log.warn("Détection du point de rupture interrompue : {}", e.getClass().getSimpleName());
            }
        });
        return thread::interrupt;
    }
}
