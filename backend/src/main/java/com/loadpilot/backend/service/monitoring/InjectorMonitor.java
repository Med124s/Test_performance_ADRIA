package com.loadpilot.backend.service.monitoring;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Surveillance de LoadPilot lui-meme pendant une execution (voir InjectorTrack) : CPU du processus et
 * de la machine (com.sun.management.OperatingSystemMXBean), tas de la JVM, threads de plateforme. Les
 * threads virtuels actifs, eux, sont exactement les utilisateurs virtuels actifs (un thread virtuel par
 * VU, voir LiveStats) : le JDK 21 n'expose pas de compteur dedie. Un thread virtuel par execution,
 * meme frequence que la lecture CPU / RAM de la cible (5 s par defaut) ; jamais bloquant.
 */
@Component
public class InjectorMonitor {

    private static final Logger log = LoggerFactory.getLogger(InjectorMonitor.class);
    public static final int DEFAULT_INTERVAL_SECONDS = 5;

    private final double cpuWarningPercent;

    public InjectorMonitor(@Value("${app.injector.cpu-warning-percent:85}") double cpuWarningPercent) {
        this.cpuWarningPercent = cpuWarningPercent;
    }

    public double cpuWarningPercent() {
        return cpuWarningPercent;
    }

    /** Une lecture du processus LoadPilot. */
    public static InjectorTrack.Sample sample() {
        Double process = null;
        Double machine = null;
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            process = percent(os.getProcessCpuLoad());
            machine = percent(os.getCpuLoad());
        }
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long max = heap.getMax() > 0 ? heap.getMax() : heap.getCommitted();
        return new InjectorTrack.Sample(process, machine, heap.getUsed() / (1024 * 1024), max / (1024 * 1024),
                ManagementFactory.getThreadMXBean().getThreadCount());
    }

    /** Charge [0, 1] en %, arrondie a 0,1 ; null si indisponible (valeur negative). */
    static Double percent(double load) {
        return load < 0 || Double.isNaN(load) ? null : Math.round(load * 1000) / 10.0;
    }

    /** Lecture en cours pour une execution : stop() l'arrete (fin du test). */
    public interface Session {
        void stop();
    }

    /** Demarre la lecture toutes les intervalSeconds (premiere lecture immediate) dans "track". */
    public Session start(InjectorTrack track, int intervalSeconds) {
        long intervalMs = Math.max(1, intervalSeconds) * 1000L;
        Thread thread = Thread.ofVirtual().name("injector-monitor").start(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    track.add(sample());
                    Thread.sleep(intervalMs);
                }
            } catch (InterruptedException e) {
                // fin du test
            } catch (RuntimeException e) {
                log.warn("Surveillance de l'injecteur interrompue : {}", e.getClass().getSimpleName());
            }
        });
        return thread::interrupt;
    }

    public InjectorTrack newTrack() {
        return new InjectorTrack(cpuWarningPercent);
    }
}
