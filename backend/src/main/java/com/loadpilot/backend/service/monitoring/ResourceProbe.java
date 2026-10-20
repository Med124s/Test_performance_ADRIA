package com.loadpilot.backend.service.monitoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.enums.MonitoringType;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * Lecture du CPU et de la RAM du PROCESSUS de l'application testee (une instance par execution :
 * garde la lecture precedente, le CPU Prometheus etant un compteur cumule).
 *
 * CPU : toujours en % d'UN coeur (100 % = un coeur entierement occupe, 250 % = deux coeurs et demi).
 *   Prometheus : (temps CPU cumule - precedent) / temps ecoule ; null a la premiere lecture.
 *   Actuator   : process.cpu.usage (0..1 de toute la machine) x system.cpu.count.
 * RAM : en Mo. Prometheus : process_resident_memory_bytes (memoire residente du processus) ;
 *   Actuator : jvm.memory.used (memoire utilisee par la JVM : tas + hors tas).
 *
 * Toute erreur devient une MonitoringException au message lisible (jamais le secret).
 */
public class ResourceProbe {

    static final Duration TIMEOUT = Duration.ofSeconds(3);

    /** Une lecture : null = valeur non disponible a cet instant. */
    public record Reading(long atMs, Double cpuPercent, Double memoryMb, String memoryMetric) {
    }

    private final MonitoringConfig config;
    private final HttpClient client;
    private final ObjectMapper mapper;
    private Double previousCpuSeconds;
    private long previousNanos;
    private Double cpuCount;

    public ResourceProbe(MonitoringConfig config, HttpClient client, ObjectMapper mapper) {
        this.config = config;
        this.client = client;
        this.mapper = mapper;
    }

    public Reading read() throws MonitoringException {
        return config.type() == MonitoringType.ACTUATOR ? readActuator() : readPrometheus();
    }

    private Reading readPrometheus() throws MonitoringException {
        long now = System.nanoTime();
        PrometheusMetrics metrics = PrometheusMetrics.parse(get(config.url().trim()));
        Double cpuSeconds = metrics.cpuSeconds();
        Double memoryBytes = metrics.memoryBytes();
        if (cpuSeconds == null && metrics.instantCpuPercent() == null && memoryBytes == null) {
            throw new MonitoringException("Réponse de monitoring illisible : ni process_cpu_seconds_total ni "
                    + "process_resident_memory_bytes dans " + config.url().trim() + " (est-ce bien un endpoint Prometheus ?).");
        }
        Double cpu = metrics.instantCpuPercent();
        if (cpu == null && cpuSeconds != null) {
            if (previousCpuSeconds != null && now > previousNanos && cpuSeconds >= previousCpuSeconds) {
                cpu = (cpuSeconds - previousCpuSeconds) / ((now - previousNanos) / 1e9) * 100;
            }
            previousCpuSeconds = cpuSeconds;
            previousNanos = now;
        }
        return new Reading(System.currentTimeMillis(), round(cpu), memoryBytes == null ? null : round(memoryBytes / 1024 / 1024),
                metrics.memoryMetric());
    }

    private Reading readActuator() throws MonitoringException {
        String base = ActuatorMetrics.baseUrl(config.url());
        if (cpuCount == null) {
            Double count = ActuatorMetrics.value(mapper, get(base + "/metrics/system.cpu.count"));
            cpuCount = count == null || count <= 0 ? 1.0 : count;
        }
        Double usage = ActuatorMetrics.value(mapper, get(base + "/metrics/process.cpu.usage"));
        Double used = ActuatorMetrics.value(mapper, get(base + "/metrics/jvm.memory.used"));
        if (usage == null && used == null) {
            throw new MonitoringException("Réponse Actuator illisible : process.cpu.usage et jvm.memory.used sans valeur "
                    + "(tableau « measurements » vide ou absent).");
        }
        return new Reading(System.currentTimeMillis(), usage == null ? null : round(usage * cpuCount * 100),
                used == null ? null : round(used / 1024 / 1024), "jvm.memory.used");
    }

    private String get(String url) throws MonitoringException {
        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET();
        } catch (IllegalArgumentException e) {
            throw new MonitoringException("URL de monitoring invalide : " + url + ".");
        }
        config.authHeaders().forEach(builder::header);
        // Proxy d'entreprise avec identifiant : en-tete ajoute seulement si la requete passe par lui.
        // Identifiant du proxy de l'application (ou du serveur), seulement si la requete passe par un proxy.
        config.network().applyTo(builder, URI.create(url), com.loadpilot.backend.service.http.ProxyAuthorization.current());
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code == 401 || code == 403) {
                throw new MonitoringException("Monitoring refusé (HTTP " + code + ") : vérifiez l'authentification du monitoring.");
            }
            if (code == 404) {
                throw new MonitoringException("Endpoint de monitoring introuvable (HTTP 404) : " + url + ".");
            }
            if (code >= 400) {
                throw new MonitoringException("Le monitoring a répondu avec une erreur (HTTP " + code + ") : " + url + ".");
            }
            return response.body();
        } catch (HttpTimeoutException e) {
            throw new MonitoringException("Monitoring trop lent : pas de réponse en " + TIMEOUT.toSeconds() + " s (" + url + ").");
        } catch (ConnectException e) {
            throw new MonitoringException("Monitoring injoignable (connexion refusée) : " + url + ".");
        } catch (IOException e) {
            throw new MonitoringException("Monitoring injoignable : " + url + " (" + e.getClass().getSimpleName() + ").");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MonitoringException("Lecture du monitoring interrompue.");
        }
    }

    private static Double round(Double v) {
        return v == null ? null : Math.round(v * 10) / 10.0;
    }
}
