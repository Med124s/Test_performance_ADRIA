package com.loadpilot.backend.service.monitoring;

/** Mesures de LoadPilot pendant une execution : derniere lecture, CPU maximal et saturation. */
public final class InjectorTrack {

    /** Une lecture : CPU en % de la machine entiere (null si indisponible), memoire en Mo. */
    public record Sample(Double processCpuPercent, Double machineCpuPercent, long heapUsedMb, long heapMaxMb, int platformThreads) {
    }

    private final double cpuWarningPercent;
    private Sample latest;
    private Double cpuMaxPercent;
    private int samples;
    private int overloadedSamples;

    public InjectorTrack(double cpuWarningPercent) {
        this.cpuWarningPercent = cpuWarningPercent;
    }

    /** Lectures consecutives au-dessus du seuil, et System.nanoTime() de la premiere (-1 sans saturation en cours). */
    private int consecutiveOverloaded;
    private long overloadedSinceNanos = -1;

    public void add(Sample sample) {
        add(sample, System.nanoTime());
    }

    /** "atNanos" : System.nanoTime() de la lecture. */
    public synchronized void add(Sample sample, long atNanos) {
        latest = sample;
        samples++;
        Double cpu = sample.processCpuPercent();
        if (cpu != null) {
            cpuMaxPercent = cpuMaxPercent == null ? cpu : Math.max(cpuMaxPercent, cpu);
            if (cpu >= cpuWarningPercent) overloadedSamples++;
        }
        if (cpu != null && cpu >= cpuWarningPercent) {
            if (consecutiveOverloaded++ == 0) overloadedSinceNanos = atNanos;
        } else {
            consecutiveOverloaded = 0;
            overloadedSinceNanos = -1;
        }
    }

    /** Lectures consecutives du CPU de LoadPilot au-dessus du seuil, jusqu'a la derniere. */
    public synchronized int consecutiveOverloadedSamples() {
        return consecutiveOverloaded;
    }

    /** System.nanoTime() de la premiere lecture de la saturation en cours, -1 sans saturation en cours. */
    public synchronized long overloadedSinceNanos() {
        return overloadedSinceNanos;
    }

    public synchronized Sample latest() {
        return latest;
    }

    public synchronized Double cpuMaxPercent() {
        return cpuMaxPercent;
    }

    /** Le CPU de LoadPilot a-t-il atteint le seuil au moins une fois pendant ce test ? */
    public synchronized boolean overloaded() {
        return overloadedSamples > 0;
    }

    public synchronized int samples() {
        return samples;
    }

    public double cpuWarningPercent() {
        return cpuWarningPercent;
    }

    /** Avertissement affiche dans le suivi en direct, ou null si l'injecteur n'a jamais sature. */
    public synchronized String warning() {
        if (overloadedSamples == 0) return null;
        return "Injecteur saturé : le CPU de LoadPilot a atteint " + Math.round(cpuMaxPercent) + " % (seuil "
                + Math.round(cpuWarningPercent) + " %). Les temps de réponse mesurés peuvent être faussés : "
                + "réduisez la charge ou utilisez une machine plus puissante.";
    }
}
