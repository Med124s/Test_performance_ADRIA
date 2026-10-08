package com.loadpilot.backend.service.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.enums.MonitoringAuthType;
import com.loadpilot.backend.enums.MonitoringType;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Lecture du CPU / RAM de l'application testee : formats Prometheus et Actuator, erreurs lisibles. */
class ResourceProbeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private HttpServer server;
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    /** Extrait reel de prom-client (metriques par defaut d'un processus Node). */
    static String promClient(double cpuSeconds, long rssBytes) {
        return """
                # HELP process_cpu_user_seconds_total Total user CPU time spent in seconds.
                # TYPE process_cpu_user_seconds_total counter
                process_cpu_user_seconds_total 1.2
                # HELP process_cpu_seconds_total Total user and system CPU time spent in seconds.
                # TYPE process_cpu_seconds_total counter
                process_cpu_seconds_total %s
                # HELP process_resident_memory_bytes Resident memory size in bytes.
                # TYPE process_resident_memory_bytes gauge
                process_resident_memory_bytes %d
                nodejs_heap_size_used_bytes 21345678
                nodejs_eventloop_lag_p99_seconds{quantile="0.99",note="a } b"} 0.012
                """.formatted(cpuSeconds, rssBytes);
    }

    @Test
    void prometheus_parsesStandardProcessMetrics_sumsLabelledSeries_andIgnoresComments() {
        PrometheusMetrics m = PrometheusMetrics.parse(promClient(3.5, 104_857_600));
        assertThat(m.cpuSeconds()).isEqualTo(3.5);
        assertThat(m.memoryMetric()).isEqualTo("process_resident_memory_bytes");
        assertThat(m.memoryBytes()).isEqualTo(104_857_600d);
        assertThat(m.get("nodejs_eventloop_lag_p99_seconds")).isEqualTo(0.012);

        PrometheusMetrics jvm = PrometheusMetrics.parse("""
                jvm_memory_used_bytes{area="heap",id="G1 Eden Space"} 1.048576E7
                jvm_memory_used_bytes{area="nonheap",id="Metaspace"} 5242880.0
                process_cpu_usage 0.05
                system_cpu_count 8.0
                """);
        assertThat(jvm.memoryMetric()).isEqualTo("jvm_memory_used_bytes");
        assertThat(jvm.memoryBytes()).isEqualTo(15_728_640d);
        assertThat(jvm.instantCpuPercent()).isEqualTo(40.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void actuator_readsTheValueInsideTheMeasurementsArray() {
        String json = """
                {"name":"jvm.memory.used","baseUnit":"bytes","measurements":[{"statistic":"COUNT","value":3},
                {"statistic":"VALUE","value":2.62144E8}],"availableTags":[{"tag":"area","values":["heap","nonheap"]}]}""";
        assertThat(ActuatorMetrics.value(MAPPER, json)).isEqualTo(262_144_000d);
        assertThat(ActuatorMetrics.value(MAPPER, "{\"measurements\":[]}")).isNull();
        assertThat(ActuatorMetrics.value(MAPPER, "pas du json")).isNull();
        assertThat(ActuatorMetrics.baseUrl("http://h:8080/actuator/metrics/")).isEqualTo("http://h:8080/actuator");
        assertThat(ActuatorMetrics.baseUrl("http://h:8080/actuator")).isEqualTo("http://h:8080/actuator");
    }

    @Test
    void prometheusProbe_cpuFromTwoReadings_inPercentOfOneCore_memoryInMb_withBearerToken() throws Exception {
        AtomicReference<Double> cpuSeconds = new AtomicReference<>(10.0);
        server = start("/metrics", 200, () -> promClient(cpuSeconds.get(), 200L * 1024 * 1024));
        ResourceProbe probe = new ResourceProbe(config(MonitoringType.PROMETHEUS, "/metrics", MonitoringAuthType.BEARER), client, MAPPER);

        ResourceProbe.Reading first = probe.read();
        assertThat(first.cpuPercent()).as("compteur cumule : pas de CPU a la premiere lecture").isNull();
        assertThat(first.memoryMb()).isEqualTo(200.0);
        Thread.sleep(500);
        cpuSeconds.set(10.25); // 0,25 s de CPU en ~0,5 s = ~50 % d'un coeur
        ResourceProbe.Reading second = probe.read();
        assertThat(second.cpuPercent()).isBetween(35.0, 52.0);
        assertThat(authHeaders).containsOnly("Bearer jeton-secret");
    }

    @Test
    void actuatorProbe_cpuUsageTimesCoreCount_andJvmMemoryUsed() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/actuator/metrics/system.cpu.count", ex -> reply(ex, 200, "{\"measurements\":[{\"statistic\":\"VALUE\",\"value\":4}]}"));
        server.createContext("/actuator/metrics/process.cpu.usage", ex -> reply(ex, 200, "{\"measurements\":[{\"statistic\":\"VALUE\",\"value\":0.125}]}"));
        server.createContext("/actuator/metrics/jvm.memory.used", ex -> reply(ex, 200, "{\"measurements\":[{\"statistic\":\"VALUE\",\"value\":3.145728E8}]}"));
        server.start();
        ResourceProbe.Reading r = new ResourceProbe(config(MonitoringType.ACTUATOR, "/actuator/", MonitoringAuthType.NONE), client, MAPPER).read();
        assertThat(r.cpuPercent()).isEqualTo(50.0);
        assertThat(r.memoryMb()).isEqualTo(300.0);
        assertThat(r.memoryMetric()).isEqualTo("jvm.memory.used");
    }

    @Test
    void unavailableMonitoring_givesClearMessages_neverTheSecret() throws Exception {
        server = start("/metrics", 401, () -> "{}");
        ResourceProbe refused = new ResourceProbe(config(MonitoringType.PROMETHEUS, "/metrics", MonitoringAuthType.BEARER), client, MAPPER);
        assertThatThrownBy(refused::read).isInstanceOf(MonitoringException.class)
                .hasMessage("Monitoring refusé (HTTP 401) : vérifiez l'authentification du monitoring.");

        server.stop(0);
        server = start("/metrics", 200, () -> "<html>pas des metriques</html>");
        assertThatThrownBy(new ResourceProbe(config(MonitoringType.PROMETHEUS, "/metrics", MonitoringAuthType.NONE), client, MAPPER)::read)
                .hasMessageStartingWith("Réponse de monitoring illisible");

        int closed;
        try (ServerSocket s = new ServerSocket(0)) {
            closed = s.getLocalPort();
        }
        MonitoringConfig down = new MonitoringConfig(MonitoringType.PROMETHEUS, "http://localhost:" + closed + "/metrics",
                MonitoringAuthType.BEARER, null, "jeton-secret", 5);
        assertThatThrownBy(new ResourceProbe(down, client, MAPPER)::read)
                .hasMessageStartingWith("Monitoring injoignable").hasMessageNotContaining("jeton-secret");
        assertThat(down.toString()).doesNotContain("jeton-secret");

        ResourceTrack track = new ResourceTrack("PROMETHEUS", 5);
        track.failure("Monitoring injoignable (connexion refusée) : http://localhost/metrics.");
        assertThat(track.message()).startsWith("Monitoring indisponible : Monitoring injoignable")
                .endsWith("L'exécution a continué normalement, sans CPU ni RAM.");
        track.add(new ResourceProbe.Reading(1, 12.0, 100.0, "process_resident_memory_bytes"));
        assertThat(track.message()).startsWith("Monitoring partiellement indisponible : 1 lecture(s) sur 2 ont échoué");
    }

    @Test
    void track_beyondTheLimit_averagesPairs_soTheWholeRunStaysCovered() {
        ResourceTrack track = new ResourceTrack("PROMETHEUS", 1);
        for (int i = 0; i <= ResourceTrack.MAX_SAMPLES; i++) track.add(new ResourceProbe.Reading(i * 1000L, (double) (i % 2) * 10, 100.0, null));
        assertThat(track.samples()).hasSizeLessThanOrEqualTo(ResourceTrack.MAX_SAMPLES / 2 + 1);
        assertThat(track.samples().get(0).cpuPercent()).isEqualTo(5.0);
        assertThat(track.samples().get(track.samples().size() - 1).atMs()).isEqualTo(ResourceTrack.MAX_SAMPLES * 1000L);
    }

    // ------------------------------------------------------------ outils

    private MonitoringConfig config(MonitoringType type, String path, MonitoringAuthType auth) {
        return new MonitoringConfig(type, "http://localhost:" + server.getAddress().getPort() + path, auth, null,
                auth == MonitoringAuthType.NONE ? null : "jeton-secret", 5);
    }

    private HttpServer start(String path, int status, java.util.function.Supplier<String> body) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        s.createContext(path, ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (auth != null) authHeaders.add(auth);
            reply(ex, status, body.get());
        });
        s.start();
        return s;
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
