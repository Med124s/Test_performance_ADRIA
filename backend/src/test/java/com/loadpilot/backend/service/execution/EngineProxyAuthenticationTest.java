package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.enums.HttpMethod;
import com.loadpilot.backend.service.auth.ApplicationAuthSpec;
import com.loadpilot.backend.service.http.NetworkProfile;
import com.loadpilot.backend.service.http.ProxyAuthorization;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proxy d'entreprise avec identifiant (Basic), de bout en bout dans le moteur : faux proxy local qui
 * repond 407 sans identifiant correct, sinon joue lui-meme le role de l'application (200 sur /ok, 401
 * sans WWW-Authenticate sur /refus). Proxy HTTP (requete en URL absolue) : meme mecanisme d'en-tete
 * que le CONNECT d'un tunnel HTTPS, verifie separement avec Java 21 (voir ProxyAuthorization).
 */
class EngineProxyAuthenticationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String EXPECTED = "Basic " + Base64.getEncoder().encodeToString("agent:s3cret".getBytes(StandardCharsets.UTF_8));

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<String> proxySaw = new CopyOnWriteArrayList<>();
    private ServerSocket proxy;
    private final Map<String, String> savedProperties = new java.util.HashMap<>();

    @BeforeEach
    void startProxy() throws IOException {
        proxy = new ServerSocket(0);
        Thread.ofVirtual().start(() -> {
            while (!proxy.isClosed()) {
                try (Socket s = proxy.accept()) {
                    answer(s);
                } catch (IOException e) {
                    return;
                }
            }
        });
        // Les requetes http:// vers « app.test » passent par le faux proxy (localhost exclu de rien).
        for (String key : List.of("http.proxyHost", "http.proxyPort", "http.nonProxyHosts")) savedProperties.put(key, System.getProperty(key));
        System.setProperty("http.proxyHost", "localhost");
        System.setProperty("http.proxyPort", String.valueOf(proxy.getLocalPort()));
        System.setProperty("http.nonProxyHosts", "");
    }

    @AfterEach
    void stopProxy() throws IOException {
        savedProperties.forEach((key, value) -> {
            if (value == null) System.clearProperty(key);
            else System.setProperty(key, value);
        });
        proxy.close();
        executor.shutdown();
    }

    private void answer(Socket s) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
        String requestLine = in.readLine();
        if (requestLine == null) return; // simple verification de connexion au proxy, sans requete
        String auth = null;
        for (String line; (line = in.readLine()) != null && !line.isEmpty(); ) {
            if (line.toLowerCase().startsWith("proxy-authorization:")) auth = line.substring(20).trim();
        }
        proxySaw.add(requestLine + " | " + (auth == null ? "sans identifiant" : EXPECTED.equals(auth) ? "identifiant correct" : "identifiant faux"));
        String status = !EXPECTED.equals(auth) ? "407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"entreprise\""
                : requestLine.contains("/refus") ? "401 Unauthorized" : "200 OK";
        // Connexion automatique : le faux proxy joue aussi l'endpoint de login de l'application.
        String body = EXPECTED.equals(auth) && requestLine.contains("/login") ? "{\"data\":{\"token\":\"t-1\"}}" : "";
        String type = body.isEmpty() ? "" : "Content-Type: application/json\r\n";
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 " + status + "\r\n" + type + "Content-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body)
                .getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private ScenarioExecutionOutcome run(ProxyAuthorization proxyAuthorization) {
        HttpClientExecutionEngine engine = new HttpClientExecutionEngine(5, MAPPER, executor, proxyAuthorization);
        List<StepExecutionSpec> steps = List.of(
                new StepExecutionSpec(UUID.randomUUID(), "ok", HttpMethod.GET, "/ok", null, null, null, null, null, null, null, null, null),
                new StepExecutionSpec(UUID.randomUUID(), "refus", HttpMethod.GET, "/refus", null, null, 401, null, null, null, null, null, null));
        return engine.execute(steps, "http://app.test", ApplicationAuthSpec.none(), new LoadTestSpec(1, 0, null, 1, 0, null),
                new RunningExecutionHandle(), new VariableSources(List.of(), null, Map.of(), steps));
    }

    @Test
    void withCredentials_theProxyIsAuthenticated_andATarget401StaysA401() {
        ScenarioExecutionOutcome outcome = run(ProxyAuthorization.of("agent", "s3cret", ProxySelector::getDefault));

        assertThat(proxySaw).allMatch(line -> line.endsWith("identifiant correct")).hasSize(2);
        // 401 de l'application (sans WWW-Authenticate) : une vraie reponse 401, jamais une erreur reseau.
        assertThat(outcome.stepOutcomes()).extracting(StepOutcome::httpStatus).containsExactly(200, 401);
        assertThat(outcome.stepOutcomes()).allMatch(StepOutcome::success);
    }

    // ------------------------------------------------------------ proxy propre a l'application

    /** Le serveur n'a ni proxy ni identifiant : seul le proxy de l'application peut etre utilise. */
    private void noServerProxy() {
        System.clearProperty("http.proxyHost");
        System.clearProperty("http.proxyPort");
    }

    private NetworkProfile applicationProxy() {
        return NetworkProfile.custom("localhost", proxy.getLocalPort(), "agent", "s3cret");
    }

    private static List<StepExecutionSpec> steps() {
        return List.of(
                new StepExecutionSpec(UUID.randomUUID(), "ok", HttpMethod.GET, "/ok", null, null, null, null, null, null, null, null, null),
                new StepExecutionSpec(UUID.randomUUID(), "refus", HttpMethod.GET, "/refus", null, null, 401, null, null, null, null, null, null));
    }

    @Test
    void applicationProxy_engineAndAutomaticLogin_goThroughItWithItsCredentials() {
        noServerProxy();
        HttpClientExecutionEngine engine = new HttpClientExecutionEngine(5, MAPPER, executor, ProxyAuthorization.none());
        ApplicationAuthSpec login = new ApplicationAuthSpec(com.loadpilot.backend.enums.ApplicationAuthType.LOGIN_TOKEN,
                new com.loadpilot.backend.service.auth.ApplicationAuthConfig(null, null, null, null, null, null, "/login", "{}",
                        "$.data.token", null, null));
        List<StepExecutionSpec> steps = steps();
        ScenarioExecutionOutcome outcome = engine.execute(steps, "http://app.test", login, new LoadTestSpec(1, 0, null, 1, 0, null),
                new RunningExecutionHandle(), new VariableSources(List.of(), null, Map.of(), steps), applicationProxy());

        assertThat(proxySaw).hasSize(3).allMatch(line -> line.endsWith("identifiant correct"));
        assertThat(proxySaw.get(0)).startsWith("POST http://app.test/login");
        assertThat(outcome.stepOutcomes()).extracting(StepOutcome::httpStatus).containsExactly(200, 401);
    }

    @Test
    void applicationProxy_testRun_availabilityCheck_andMonitoring_goThroughIt() {
        noServerProxy();
        TestRunPlan plan = new TestRunPlan(UUID.randomUUID(), "http://app.test", ApplicationAuthSpec.none(), steps(), Map.of(), Map.of(),
                Map.of(), List.of(1, 2), Map.of(), applicationProxy());
        assertThat(new StepTestRunner(5, 30, MAPPER, ProxyAuthorization.none()).run(plan, StepTestRunner.Listener.NONE).success()).isTrue();
        assertThat(new com.loadpilot.backend.service.http.JavaHttpClientAvailabilityChecker()
                .check("http://app.test/ok", java.time.Duration.ofSeconds(5), applicationProxy()).httpStatus()).isEqualTo(200);
        com.loadpilot.backend.service.monitoring.MonitoringConfig monitoring = new com.loadpilot.backend.service.monitoring.MonitoringConfig(
                com.loadpilot.backend.enums.MonitoringType.PROMETHEUS, "http://app.test/metrics", com.loadpilot.backend.enums.MonitoringAuthType.NONE,
                null, null, 5, applicationProxy());
        new com.loadpilot.backend.service.monitoring.ResourceMonitor(MAPPER).test(monitoring);

        assertThat(proxySaw).allMatch(line -> line.endsWith("identifiant correct"))
                .anyMatch(line -> line.startsWith("GET http://app.test/ok"))
                .anyMatch(line -> line.startsWith("GET http://app.test/metrics"));
        int seen = proxySaw.size();

        // « Monitoring sans proxy » : lecture en direct, le proxy de l'application n'est pas utilise.
        new com.loadpilot.backend.service.monitoring.ResourceMonitor(MAPPER).test(monitoring.withNetwork(NetworkProfile.direct()));
        assertThat(proxySaw).hasSize(seen);
    }

    @Test
    void noProxy_bypassesTheServerProxy_whileInheritStillUsesIt() throws IOException {
        com.sun.net.httpserver.HttpServer target = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("localhost", 0), 0);
        target.createContext("/", ex -> {
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        target.start();
        try {
            HttpClientExecutionEngine engine = new HttpClientExecutionEngine(5, MAPPER, executor,
                    ProxyAuthorization.of("agent", "s3cret", ProxySelector::getDefault));
            List<StepExecutionSpec> ok = List.of(steps().get(0));
            String url = "http://localhost:" + target.getAddress().getPort();
            ScenarioExecutionOutcome direct = engine.execute(ok, url, ApplicationAuthSpec.none(), new LoadTestSpec(1, 0, null, 1, 0, null),
                    new RunningExecutionHandle(), new VariableSources(List.of(), null, Map.of(), ok), NetworkProfile.direct());
            assertThat(direct.stepOutcomes()).extracting(StepOutcome::httpStatus).containsExactly(200);
            assertThat(proxySaw).as("aucun passage par le proxy du serveur").isEmpty();

            // Proxy du serveur (INHERIT, comme avant) : la meme requete passe par lui.
            engine.execute(ok, url, ApplicationAuthSpec.none(), new LoadTestSpec(1, 0, null, 1, 0, null),
                    new RunningExecutionHandle(), new VariableSources(List.of(), null, Map.of(), ok), NetworkProfile.inherit());
            assertThat(proxySaw).hasSize(1).allMatch(line -> line.endsWith("identifiant correct"));
        } finally {
            target.stop(0);
        }
    }

    @Test
    void withoutCredentials_nothingIsSent_asBefore() {
        ScenarioExecutionOutcome outcome = run(ProxyAuthorization.none());

        assertThat(proxySaw).hasSize(1).allMatch(line -> line.endsWith("sans identifiant"));
        assertThat(outcome.stepOutcomes()).extracting(StepOutcome::httpStatus).containsExactly(407);
    }
}
