package com.loadpilot.backend.service.execution;

import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Frein automatique : arrete l'execution quand le taux d'erreurs de la fenetre glissante depasse le seuil du scenario. */
public final class ErrorRateBrake {

    private static final Logger log = LoggerFactory.getLogger(ErrorRateBrake.class);

    /** Fenetre par defaut (secondes) quand le scenario n'en precise pas. */
    public static final int DEFAULT_WINDOW_SECONDS = 30;
    public static final int MIN_WINDOW_SECONDS = 5;
    /** Nombre minimal de requetes dans la fenetre avant de juger. */
    public static final int MIN_REQUESTS = 20;
    static final long CHECK_INTERVAL_MS = 500;
    /** Debut du message d'arret (reconnu par ExecutionStatusReason). */
    public static final String REASON_PREFIX = "Arrêt automatique : ";
    private static final int WATCHING = 0;
    private static final int TRIPPING = 1;
    private static final int STOPPED = 2;

    /** Reglage copie au lancement. */
    public record Config(double maxErrorRatePercent, int windowSeconds) {

        /** null si le frein est desactive (taux absent ou <= 0). */
        public static Config of(Double maxErrorRatePercent, Integer windowSeconds) {
            if (maxErrorRatePercent == null || maxErrorRatePercent <= 0) return null;
            int window = windowSeconds == null ? DEFAULT_WINDOW_SECONDS
                    : Math.max(MIN_WINDOW_SECONDS, Math.min(LiveStats.MAX_WINDOW_SECONDS, windowSeconds));
            return new Config(Math.min(100.0, maxErrorRatePercent), window);
        }
    }

    /** Surveillance en cours ; stop() est idempotent. */
    public interface Session {
        void stop();
    }

    private ErrorRateBrake() {
    }

    /** Motif d'arret si le seuil est depasse (strictement), null sinon. */
    public static String evaluate(Config config, LiveStats.Window window) {
        if (config == null || window == null || window.requests() < MIN_REQUESTS) return null;
        double rate = window.failures() * 100.0 / window.requests();
        if (rate <= config.maxErrorRatePercent()) return null;
        return REASON_PREFIX + ExecutionVerdict.format(ExecutionVerdict.roundedRate(window.failures(), window.requests()))
                + " % d'erreurs sur les " + config.windowSeconds() + " dernières secondes (" + window.failures() + " sur "
                + window.requests() + " requêtes), au-delà du seuil de " + ExecutionVerdict.format(config.maxErrorRatePercent())
                + " %.";
    }

    /** Demarre la surveillance de "handle" ; onTrip recoit le motif, au plus une fois. */
    public static Session start(Config config, RunningExecutionHandle handle, Consumer<String> onTrip) {
        if (config == null) return () -> { };
        // WATCHING -> TRIPPING (declenchement) ou STOPPED (fin d'execution), une seule fois.
        java.util.concurrent.atomic.AtomicInteger state = new java.util.concurrent.atomic.AtomicInteger(WATCHING);
        Thread thread = Thread.ofVirtual().name("error-rate-brake").start(() -> {
            try {
                while (state.get() == WATCHING && !handle.isCancelled()) {
                    Thread.sleep(CHECK_INTERVAL_MS);
                    LiveStats stats = handle.liveStats();
                    if (stats == null || handle.isPaused()) continue;
                    String reason = evaluate(config, stats.recentWindow(config.windowSeconds()));
                    if (reason != null && !handle.isCancelled() && state.compareAndSet(WATCHING, TRIPPING)) {
                        onTrip.accept(reason);
                        return;
                    }
                }
            } catch (InterruptedException e) {
                // stop() : fin de l'execution.
            } catch (RuntimeException e) {
                log.warn("Frein automatique interrompu : {}", e.getClass().getSimpleName());
            }
        });
        return () -> {
            if (state.compareAndSet(WATCHING, STOPPED)) thread.interrupt();
        };
    }
}
