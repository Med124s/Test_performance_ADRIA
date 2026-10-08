package com.loadpilot.backend.service.monitoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.dto.response.MonitoringTestResponse;
import java.net.http.HttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Lecture periodique du CPU / RAM de l'application testee pendant une execution, et
 * « Tester le monitoring ». Un thread virtuel par execution surveillee ; jamais bloquant pour
 * l'execution : une lecture en erreur est simplement comptee (voir ResourceTrack.message()).
 */
@Component
public class ResourceMonitor {

    private static final Logger log = LoggerFactory.getLogger(ResourceMonitor.class);

    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(ResourceProbe.TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public ResourceMonitor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Lecture en cours pour une execution : stop() l'arrete (appele a la fin du test). */
    public static final class Session {
        private final Thread thread;
        private volatile boolean stopped;

        private Session(Thread thread) {
            this.thread = thread;
        }

        public void stop() {
            stopped = true;
            thread.interrupt();
            try {
                thread.join(ResourceProbe.TIMEOUT.toMillis() + 500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Demarre la lecture toutes les intervalSeconds (premiere lecture immediate). */
    public Session start(MonitoringConfig config, ResourceTrack track) {
        ResourceProbe probe = new ResourceProbe(config, client, mapper);
        Session[] holder = new Session[1];
        Thread thread = Thread.ofVirtual().name("monitoring-" + config.type()).unstarted(() -> {
            long intervalMs = Math.max(1, config.intervalSeconds()) * 1000L;
            long next = System.currentTimeMillis();
            while (!holder[0].stopped) {
                try {
                    track.add(probe.read());
                } catch (MonitoringException e) {
                    if (holder[0].stopped) break;
                    track.failure(e.getMessage());
                    log.debug("Lecture du monitoring en echec : {}", e.getMessage());
                }
                next += intervalMs;
                long wait = next - System.currentTimeMillis();
                if (wait > 0) {
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException e) {
                        break;
                    }
                } else {
                    next = System.currentTimeMillis(); // lecture lente : pas de rattrapage en rafale
                }
            }
        });
        holder[0] = new Session(thread);
        thread.start();
        return holder[0];
    }

    /** Deux lectures a une seconde d'intervalle (le CPU Prometheus a besoin de deux lectures). */
    public MonitoringTestResponse test(MonitoringConfig config) {
        if (!config.enabled()) {
            return new MonitoringTestResponse(false, "Choisissez un type de monitoring et indiquez son URL.", null, null, null);
        }
        ResourceProbe probe = new ResourceProbe(config, client, mapper);
        try {
            ResourceProbe.Reading first = probe.read();
            Thread.sleep(1000);
            ResourceProbe.Reading second = probe.read();
            Double cpu = second.cpuPercent() != null ? second.cpuPercent() : first.cpuPercent();
            Double memory = second.memoryMb() != null ? second.memoryMb() : first.memoryMb();
            String found = (cpu != null ? "CPU " + cpu + " % d'un cœur" : "CPU non disponible")
                    + ", " + (memory != null ? "RAM " + memory + " Mo" : "RAM non disponible");
            boolean complete = cpu != null && memory != null;
            return new MonitoringTestResponse(true, (complete ? "Monitoring opérationnel : " : "Monitoring joignable mais incomplet : ")
                    + found + ".", cpu, memory, second.memoryMetric());
        } catch (MonitoringException e) {
            return new MonitoringTestResponse(false, e.getMessage(), null, null, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new MonitoringTestResponse(false, "Test du monitoring interrompu.", null, null, null);
        }
    }
}
