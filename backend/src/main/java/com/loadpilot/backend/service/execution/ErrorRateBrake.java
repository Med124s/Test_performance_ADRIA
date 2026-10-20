package com.loadpilot.backend.service.execution;

import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Frein automatique sur taux d'erreurs (reglage optionnel du scenario, desactive par defaut) :
 * pendant l'execution, un thread virtuel lit toutes les 500 ms la fenetre glissante du suivi en
 * direct (LiveStats.recentWindow - aucun calcul supplementaire sur le chemin des requetes) et,
 * si le taux d'erreurs des N dernieres secondes depasse le seuil, declenche l'annulation par le
 * meme chemin que l'annulation manuelle (voir ExecutionServiceImpl.cancelForErrorRate), avec un
 * motif distinct. Il protege l'application testee : ce n'est pas un seuil de reussite (ceux-ci
 * sont juges a la fin, sur toute l'execution - voir ExecutionVerdict).
 */
public final class ErrorRateBrake {

    private static final Logger log = LoggerFactory.getLogger(ErrorRateBrake.class);

    /** Fenetre par defaut (secondes) quand le scenario n'en precise pas. */
    public static final int DEFAULT_WINDOW_SECONDS = 30;
    public static final int MIN_WINDOW_SECONDS = 5;
    /** Requetes minimales dans la fenetre avant de juger : evite un arret sur 1 erreur sur 2 au demarrage. */
    public static final int MIN_REQUESTS = 20;
    static final long CHECK_INTERVAL_MS = 500;
    /** Debut du message d'arret (reconnu par ExecutionStatusReason). */
    public static final String REASON_PREFIX = "Arrêt automatique : ";
    private static final int WATCHING = 0;
    private static final int TRIPPING = 1;
    private static final int STOPPED = 2;

    /** Reglage fige au lancement. */
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

    /**
     * Decision pure : motif d'arret si le seuil est depasse (strictement), null sinon. Le seuil
     * de 100 % ne se declenche donc jamais (garde-fou inutile, mais pas une erreur).
     */
    public static String evaluate(Config config, LiveStats.Window window) {
        if (config == null || window == null || window.requests() < MIN_REQUESTS) return null;
        double rate = window.failures() * 100.0 / window.requests();
        if (rate <= config.maxErrorRatePercent()) return null;
        return REASON_PREFIX + ExecutionVerdict.format(ExecutionVerdict.roundedRate(window.failures(), window.requests()))
                + " % d'erreurs sur les " + config.windowSeconds() + " dernières secondes (" + window.failures() + " sur "
                + window.requests() + " requêtes), au-delà du seuil de " + ExecutionVerdict.format(config.maxErrorRatePercent())
                + " %.";
    }

    /**
     * Demarre la surveillance de "handle". onTrip recoit le motif, au plus une fois ; la
     * surveillance s'arrete d'elle-meme si l'execution est annulee par ailleurs.
     */
    public static Session start(Config config, RunningExecutionHandle handle, Consumer<String> onTrip) {
        if (config == null) return () -> { };
        // WATCHING -> TRIPPING (declenchement) ou STOPPED (fin d'execution), une seule fois. stop()
        // n'interrompt que l'attente : jamais l'annulation en cours d'ecriture en base (une interruption
        // pendant une E/S reseau d'un thread virtuel fermerait la connexion JDBC).
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
                // stop() : fin normale de l'execution.
            } catch (RuntimeException e) {
                // Le frein ne doit jamais faire echouer le test : il cesse simplement de surveiller.
                log.warn("Frein automatique interrompu : {}", e.getClass().getSimpleName());
            }
        });
        return () -> {
            if (state.compareAndSet(WATCHING, STOPPED)) thread.interrupt();
        };
    }
}
