package com.loadpilot.backend.service.monitoring;

import java.util.ArrayList;
import java.util.List;

/**
 * Mesures CPU / RAM d'UNE execution, en memoire pendant le test (thread-safe) : lues par le
 * suivi en direct, enregistrees a la fin (ExecutionTransactionHelper.saveResources).
 * Au-dela de MAX_SAMPLES, les mesures sont moyennees deux a deux : toute la duree reste couverte.
 */
public final class ResourceTrack {

    static final int MAX_SAMPLES = 4000;

    /** Une mesure : instant (epoch ms), CPU en % d'un coeur et RAM en Mo (null = non disponible). */
    public record Sample(long atMs, Double cpuPercent, Double memoryMb) {
    }

    private final String source;
    private final int intervalSeconds;
    private final List<Sample> samples = new ArrayList<>();
    private String memoryMetric;
    private int successes;
    private int failures;
    private String lastError;

    public ResourceTrack(String source, int intervalSeconds) {
        this.source = source;
        this.intervalSeconds = intervalSeconds;
    }

    public synchronized void add(ResourceProbe.Reading reading) {
        successes++;
        if (reading.memoryMetric() != null) memoryMetric = reading.memoryMetric();
        if (reading.cpuPercent() == null && reading.memoryMb() == null) return;
        samples.add(new Sample(reading.atMs(), reading.cpuPercent(), reading.memoryMb()));
        if (samples.size() > MAX_SAMPLES) halve();
    }

    public synchronized void failure(String message) {
        failures++;
        lastError = message;
    }

    public String source() {
        return source;
    }

    public int intervalSeconds() {
        return intervalSeconds;
    }

    public synchronized String memoryMetric() {
        return memoryMetric;
    }

    public synchronized List<Sample> samples() {
        return List.copyOf(samples);
    }

    public synchronized Sample latest() {
        return samples.isEmpty() ? null : samples.get(samples.size() - 1);
    }

    /**
     * Message a afficher : null si toutes les lectures ont reussi. Monitoring jamais bloquant :
     * l'execution continue normalement dans tous les cas.
     */
    public synchronized String message() {
        if (failures == 0) return null;
        if (successes == 0) {
            return "Monitoring indisponible : " + lastError + " L'exécution a continué normalement, sans CPU ni RAM.";
        }
        return "Monitoring partiellement indisponible : " + failures + " lecture(s) sur " + (failures + successes)
                + " ont échoué (dernière erreur : " + lastError + ")";
    }

    public synchronized boolean everFailed() {
        return failures > 0;
    }

    /** Moyennes deux a deux (instant du premier, valeurs moyennes) : moitie moins de points. */
    private void halve() {
        List<Sample> merged = new ArrayList<>(samples.size() / 2 + 1);
        for (int i = 0; i < samples.size(); i += 2) {
            Sample a = samples.get(i);
            Sample b = i + 1 < samples.size() ? samples.get(i + 1) : null;
            merged.add(b == null ? a : new Sample(a.atMs(), avg(a.cpuPercent(), b.cpuPercent()), avg(a.memoryMb(), b.memoryMb())));
        }
        samples.clear();
        samples.addAll(merged);
    }

    private static Double avg(Double a, Double b) {
        if (a == null) return b;
        if (b == null) return a;
        return Math.round((a + b) / 2 * 10) / 10.0;
    }
}
