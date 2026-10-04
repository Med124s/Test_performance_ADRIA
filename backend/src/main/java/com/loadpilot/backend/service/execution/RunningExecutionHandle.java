package com.loadpilot.backend.service.execution;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;

/**
 * Poignee reelle de controle d'une Execution en cours - un seul objet par
 * Execution, partage entre le thread qui orchestre l'execution (voir
 * ExecutionServiceImpl) et le thread HTTP qui traite une eventuelle
 * demande d'annulation (POST /api/executions/{id}/cancel) ou de pause.
 *
 * "cancelled" est verifie de maniere cooperative par chaque utilisateur
 * virtuel (voir HttpClientExecutionEngine.runVirtualUser) AVANT chaque
 * etape/iteration ; "futures" permet EN PLUS d'interrompre reellement une
 * requete HTTP deja en vol (Future.cancel(true) interrompt le thread
 * virtuel, java.net.http.HttpClient.send(...) reagit a l'interruption).
 * Sans ce deuxieme mecanisme, l'annulation attendrait la fin de la requete
 * HTTP en cours avant de prendre effet.
 *
 * Pause (cooperative, jamais d'interruption) : chaque VU appelle
 * awaitIfPaused() juste avant d'envoyer une requete - une requete deja en vol
 * recoit sa reponse, aucune nouvelle requete ne part pendant la pause. Les
 * attentes (ramp-up, think time) passent par sleepActive(), qui ne decompte
 * pas le temps de pause : le test reprend exactement la ou il s'etait arrete.
 */
public final class RunningExecutionHandle {

    private volatile boolean cancelled = false;
    private volatile String cancellationReason;
    private final List<Future<?>> futures = new CopyOnWriteArrayList<>();

    private final Object pauseLock = new Object();
    private boolean paused = false;
    /** System.nanoTime() du debut de la pause en cours. */
    private long pausedSinceNanos;
    /** Somme des pauses terminees, en nanosecondes. */
    private long completedPauseNanos;

    private volatile LiveStats liveStats;
    private volatile RequestDetailRecorder detailRecorder;

    // Chronologie (heure murale, ms) : activite de chaque VU et pauses - enregistree a la fin de
    // l'execution pour l'analyse dans le temps (utilisateurs actifs a chaque instant, pauses grisees).
    private final java.util.Map<Integer, long[]> virtualUserIntervals = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<long[]> pauseIntervals = new CopyOnWriteArrayList<>();

    /** Chronologie de l'execution ; un intervalle encore ouvert est ferme a "nowMs". */
    public record Timeline(List<long[]> virtualUsers, List<long[]> pauses) {
    }

    public void virtualUserStarted(int virtualUser) {
        virtualUserIntervals.put(virtualUser, new long[] {System.currentTimeMillis(), 0L});
    }

    public void virtualUserFinished(int virtualUser) {
        long[] interval = virtualUserIntervals.get(virtualUser);
        if (interval != null) interval[1] = System.currentTimeMillis();
    }

    public Timeline timeline(long nowMs) {
        List<long[]> vus = new java.util.ArrayList<>();
        virtualUserIntervals.values().forEach(v -> vus.add(new long[] {v[0], v[1] == 0 ? nowMs : v[1]}));
        List<long[]> pauses = new java.util.ArrayList<>();
        pauseIntervals.forEach(p -> pauses.add(new long[] {p[0], p[1] == 0 ? nowMs : p[1]}));
        return new Timeline(vus, pauses);
    }

    /** Politique de detail des requetes de cette execution (null = aucun detail). */
    public RequestDetailRecorder detailRecorder() {
        return detailRecorder;
    }

    public void detailRecorder(RequestDetailRecorder recorder) {
        this.detailRecorder = recorder;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** Motif de l'annulation (null = annulation par l'utilisateur). */
    public String cancellationReason() {
        return cancellationReason;
    }

    public void registerFuture(Future<?> future) {
        futures.add(future);
        // Cas limite reel : l'annulation a ete demandee ENTRE la creation de
        // cette poignee et l'enregistrement de ce future precis - sans ce
        // rattrapage, ce VU particulier ignorerait l'annulation deja actee.
        if (cancelled) {
            future.cancel(true);
        }
    }

    /** Demarre les compteurs du suivi en direct (appele par le moteur au lancement des VU). */
    public LiveStats startLiveStats(List<StepExecutionSpec> steps) {
        liveStats = new LiveStats(steps);
        return liveStats;
    }

    /** Compteurs en direct, ou null tant que le moteur n'a pas demarre. */
    public LiveStats liveStats() {
        return liveStats;
    }

    /** Demande une annulation REELLE : positionne le drapeau cooperatif ET
     * interrompt immediatement tous les utilisateurs virtuels deja lances
     * (y compris ceux bloques par une pause). */
    public void requestCancellation() {
        requestCancellation(null);
    }

    public void requestCancellation(String reason) {
        if (!cancelled) {
            cancellationReason = reason;
        }
        cancelled = true;
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
        futures.forEach(f -> f.cancel(true));
    }

    // ------------------------------------------------------------ pause

    /** Met en pause ; false si deja en pause ou annulee. */
    public boolean pause() {
        synchronized (pauseLock) {
            if (paused || cancelled) return false;
            paused = true;
            pausedSinceNanos = System.nanoTime();
            pauseIntervals.add(new long[] {System.currentTimeMillis(), 0L});
            return true;
        }
    }

    /** Reprend ; false si l'execution n'etait pas en pause. */
    public boolean resume() {
        synchronized (pauseLock) {
            if (!paused) return false;
            completedPauseNanos += System.nanoTime() - pausedSinceNanos;
            paused = false;
            if (!pauseIntervals.isEmpty()) pauseIntervals.get(pauseIntervals.size() - 1)[1] = System.currentTimeMillis();
            pauseLock.notifyAll();
            return true;
        }
    }

    public boolean isPaused() {
        synchronized (pauseLock) {
            return paused;
        }
    }

    /** Temps total passe en pause, pause en cours comprise (nanosecondes). */
    public long pausedNanos() {
        synchronized (pauseLock) {
            return completedPauseNanos + (paused ? System.nanoTime() - pausedSinceNanos : 0L);
        }
    }

    /**
     * Temps de travail (ns) depuis startNanos, pauses exclues, calcule avec UNE seule lecture
     * d'horloge : pendant une pause la valeur est exactement constante (progression figee).
     */
    public long activeNanosSince(long startNanos) {
        synchronized (pauseLock) {
            long now = paused ? pausedSinceNanos : System.nanoTime();
            return Math.max(0L, now - startNanos - completedPauseNanos);
        }
    }

    /**
     * Bloque tant que l'execution est en pause. Renvoie false si l'execution est
     * annulee (ou le thread interrompu) : l'appelant doit alors s'arreter.
     */
    public boolean awaitIfPaused() {
        synchronized (pauseLock) {
            while (paused && !cancelled) {
                try {
                    pauseLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return !cancelled;
    }

    /**
     * Attente reelle de "millis" millisecondes de temps ACTIF : le temps passe en
     * pause n'est pas decompte (un VU en ramp-up ou en think time reprend son
     * attente la ou il l'avait laissee). false si annulee ou interrompue.
     */
    public boolean sleepActive(long millis) {
        long remaining = millis * 1_000_000L;
        while (remaining > 0) {
            if (!awaitIfPaused()) return false;
            long chunk = Math.min(remaining, 100_000_000L);
            long pausedBefore = pausedNanos();
            long start = System.nanoTime();
            try {
                Thread.sleep(chunk / 1_000_000L, (int) (chunk % 1_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            long active = (System.nanoTime() - start) - (pausedNanos() - pausedBefore);
            remaining -= Math.max(0L, active);
        }
        return !cancelled;
    }
}
