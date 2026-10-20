package com.loadpilot.backend.service.monitoring;

/**
 * Mesures de LoadPilot lui-meme (l'injecteur) pendant UNE execution : derniere lecture, CPU maximal,
 * et saturation (CPU du processus au-dela du seuil au moins une fois). Un injecteur sature fausse
 * les temps de reponse mesures (les requetes attendent un processeur, pas la cible) : c'est ce que
 * le suivi en direct signale. Ecrit par un seul thread (InjectorMonitor), lu par le suivi en direct.
 */
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

    public synchronized void add(Sample sample) {
        latest = sample;
        samples++;
        Double cpu = sample.processCpuPercent();
        if (cpu != null) {
            cpuMaxPercent = cpuMaxPercent == null ? cpu : Math.max(cpuMaxPercent, cpu);
            if (cpu >= cpuWarningPercent) overloadedSamples++;
        }
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
