package com.loadpilot.backend.service.execution;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;

/** Controle d'une execution en cours : annulation, pause, compteurs en direct, mesures et chronologie. */
public final class RunningExecutionHandle {

    private volatile boolean cancelled = false;
    private volatile String cancellationReason;
    /** Utilisateur qui a demande l'annulation (« Annulée par … ») ; null = annulation automatique. */
    private volatile String cancelledBy;
    private final List<Future<?>> futures = new CopyOnWriteArrayList<>();
    private final Object cancelLock = new Object();

    private final Object pauseLock = new Object();
    private boolean paused = false;
    /** System.nanoTime() du debut de la pause en cours. */
    private long pausedSinceNanos;
    /** Somme des pauses terminees, en nanosecondes. */
    private long completedPauseNanos;

    private volatile LiveStats liveStats;
    private volatile RequestDetailRecorder detailRecorder;
    private volatile com.loadpilot.backend.service.monitoring.ResourceTrack resources;

    // Chronologie (heure murale, ms) : activite de chaque utilisateur virtuel et pauses.
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

    /** Mesures CPU / RAM de l'application testee (null : monitoring non configure). */
    public com.loadpilot.backend.service.monitoring.ResourceTrack resources() {
        return resources;
    }

    public void resources(com.loadpilot.backend.service.monitoring.ResourceTrack track) {
        this.resources = track;
    }

    /** Ecriture des resultats au fil du test ; null = resultats gardes en memoire jusqu'a la fin. */
    private volatile ResultStream resultStream;

    public ResultStream resultStream() {
        return resultStream;
    }

    public void resultStream(ResultStream stream) {
        this.resultStream = stream;
    }

    private volatile com.loadpilot.backend.service.monitoring.InjectorTrack injector;

    /** Mesures de LoadPilot lui-meme pendant le test (null tant que non demarre). */
    public com.loadpilot.backend.service.monitoring.InjectorTrack injector() {
        return injector;
    }

    public void injector(com.loadpilot.backend.service.monitoring.InjectorTrack track) {
        this.injector = track;
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

    public String cancelledBy() {
        return cancelledBy;
    }

    /** Annulation demandee par un utilisateur ; true si c'est cette demande qui a annule. */
    public boolean requestCancellationBy(String username) {
        return requestCancellation(null, username);
    }

    public void registerFuture(Future<?> future) {
        futures.add(future);
        // Annulation demandee avant l'enregistrement de cette tache : elle est annulee tout de suite.
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

    /** Annule l'execution : interrompt tous les utilisateurs virtuels deja lances. */
    public void requestCancellation() {
        requestCancellation(null);
    }

    /** Annulation automatique avec son motif ; true si c'est cette demande qui a annule. */
    public boolean requestCancellation(String reason) {
        return requestCancellation(reason, null);
    }

    /** La premiere demande d'annulation l'emporte : son motif et son auteur sont gardes. */
    private boolean requestCancellation(String reason, String username) {
        boolean first;
        synchronized (cancelLock) {
            first = !cancelled;
            if (first) {
                cancellationReason = reason;
                cancelledBy = username;
                cancelled = true;
            }
        }
        interruptAll();
        return first;
    }

    private void interruptAll() {
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
        futures.forEach(f -> f.cancel(true));
    }

    // ---- Fin de la montee en charge (mode stress) ----

    private volatile boolean stopRequested = false;

    /**
     * Fin normale d'un test de stress (ce n'est pas une annulation) : les utilisateurs virtuels terminent leur
     * requete en cours, qui est comptee, puis s'arretent au lieu de commencer la suivante.
     */
    public void requestStop() {
        stopRequested = true;
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
    }

    public boolean isStopRequested() {
        return stopRequested;
    }

    private volatile boolean stressMode = false;

    /** Execution en mode stress (connu des l'enregistrement, avant le demarrage du moteur). */
    public boolean isStressMode() {
        return stressMode;
    }

    void stressMode(boolean stress) {
        this.stressMode = stress;
    }

    private volatile StressRamp stressRamp;

    /** Montee en charge automatique (mode stress), null en mode manuel ou tant qu'elle n'a pas demarre. */
    public StressRamp stressRamp() {
        return stressRamp;
    }

    void stressRamp(StressRamp ramp) {
        this.stressRamp = ramp;
    }

    // ---- Pause ----

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

    /** Temps de travail (ns) depuis startNanos, pauses exclues. */
    public long activeNanosSince(long startNanos) {
        synchronized (pauseLock) {
            long now = paused ? pausedSinceNanos : System.nanoTime();
            return Math.max(0L, now - startNanos - completedPauseNanos);
        }
    }

    /** Attend la fin de la pause ; false si l'execution est annulee ou arretee (fin d'un test de stress). */
    public boolean awaitIfPaused() {
        synchronized (pauseLock) {
            while (paused && !cancelled && !stopRequested) {
                try {
                    pauseLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return !cancelled && !stopRequested;
    }

    /** Attend "millis" millisecondes de temps actif (pauses exclues) ; false si annulee ou arretee. */
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
        return !cancelled && !stopRequested;
    }
}
