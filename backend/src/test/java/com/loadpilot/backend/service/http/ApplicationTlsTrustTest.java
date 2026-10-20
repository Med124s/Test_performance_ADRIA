package com.loadpilot.backend.service.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.enums.CsvDistribution;
import com.loadpilot.backend.enums.HttpMethod;
import com.loadpilot.backend.enums.ProxyMode;
import com.loadpilot.backend.service.auth.ApplicationAuthSpec;
import com.loadpilot.backend.service.execution.HttpClientExecutionEngine;
import com.loadpilot.backend.service.execution.LoadTestSpec;
import com.loadpilot.backend.service.execution.RunningExecutionHandle;
import com.loadpilot.backend.service.execution.ScenarioExecutionOutcome;
import com.loadpilot.backend.service.execution.StepExecutionSpec;
import com.loadpilot.backend.service.execution.ThrottlePolicy;
import com.loadpilot.backend.service.execution.VariableSources;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Confiance TLS par application (autorite de certification seulement) contre un vrai serveur HTTPS local
 * dont le certificat est signe par une autorite de test inconnue de la JVM.
 */
class ApplicationTlsTrustTest {

    @TempDir
    static Path dir;

    private static TestPki pki;
    private static TestPki otherPki;
    private static HttpsServer server;
    private static String baseUrl;

    @BeforeAll
    static void start() throws Exception {
        pki = TestPki.create(dir, "LoadPilot Test CA");
        otherPki = TestPki.create(dir, "Other Test CA");
        server = pki.startServer();
        baseUrl = "https://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        if (server != null) server.stop(0);
    }

    private static Application app(String pem) {
        Application application = new Application();
        application.setProxyMode(ProxyMode.NONE);
        application.setTlsCaCertificate(pem);
        return application;
    }

    private static int get(NetworkProfile profile, String url) throws IOException, InterruptedException {
        HttpClient client = profile.configure(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))).build();
        try {
            return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
        } finally {
            client.shutdownNow();
        }
    }

    @Test
    void withoutTlsSettings_nothingChanges_jvmDefaultContext_andTheUnknownAuthorityIsRefused() throws Exception {
        NetworkProfile profile = NetworkProfile.of(app(null));
        assertThat(profile.hasCustomTrust()).isFalse();
        assertThat(profile).isSameAs(NetworkProfile.direct());
        HttpClient client = profile.configure(HttpClient.newBuilder()).build();
        assertThat(client.sslContext()).as("contexte par defaut de la JVM, comme avant").isSameAs(SSLContext.getDefault());
        client.shutdownNow();

        assertThatThrownBy(() -> get(profile, baseUrl + "/"))
                .isInstanceOf(SSLHandshakeException.class);
        assertThat(NetworkProfile.of(app("   ")).hasCustomTrust()).isFalse();
    }

    @Test
    void withTheApplicationsCa_theServerIsTrusted() throws Exception {
        NetworkProfile profile = NetworkProfile.of(app(pki.caPem));
        assertThat(profile.hasCustomTrust()).isTrue();
        assertThat(get(profile, baseUrl + "/")).isEqualTo(200);
        assertThat(profile.toString()).contains("1 autorité(s)");
    }

    @Test
    void theCaStaysIsolated_otherApplicationsAndTheJvmDefaultAreUnaffected() throws Exception {
        NetworkProfile trusted = NetworkProfile.of(app(pki.caPem));
        NetworkProfile otherCa = NetworkProfile.of(app(otherPki.caPem));
        NetworkProfile plain = NetworkProfile.of(app(null));

        assertThat(get(trusted, baseUrl + "/")).isEqualTo(200);
        assertThatThrownBy(() -> get(otherCa, baseUrl + "/")).as("autre autorite : refuse").isInstanceOf(SSLHandshakeException.class);
        assertThatThrownBy(() -> get(plain, baseUrl + "/")).as("sans autorite : refuse").isInstanceOf(SSLHandshakeException.class);
        // Un client sans aucun reglage (contexte global) non plus : rien n'a ete ajoute a la JVM.
        assertThatThrownBy(() -> {
            HttpClient global = HttpClient.newHttpClient();
            try {
                global.send(HttpRequest.newBuilder(URI.create(baseUrl + "/")).build(), HttpResponse.BodyHandlers.discarding());
            } finally {
                global.shutdownNow();
            }
        }).isInstanceOf(SSLHandshakeException.class);
    }

    @Test
    void theJvmAuthoritiesRemainTrusted_andHostnameVerificationStaysOn() throws Exception {
        NetworkProfile profile = NetworkProfile.of(app(pki.caPem));
        // Autorites de la JVM conservees (ajout, jamais remplacement).
        SSLContext context = profile.configure(HttpClient.newBuilder()).build().sslContext();
        assertThat(context).isNotSameAs(SSLContext.getDefault());
        // Certificat emis pour « localhost » seulement : 127.0.0.1 est refuse malgre l'autorite reconnue.
        String byIp = "https://127.0.0.1:" + server.getAddress().getPort() + "/";
        assertThatThrownBy(() -> get(profile, byIp)).isInstanceOf(SSLHandshakeException.class);
    }

    @Test
    void monitoringWithoutProxy_keepsTheApplicationsTrust() throws Exception {
        Application application = app(pki.caPem);
        application.setMonitoringWithoutProxy(true);
        NetworkProfile monitoring = NetworkProfile.forMonitoring(application);
        assertThat(monitoring.mode()).isEqualTo(ProxyMode.NONE);
        assertThat(get(monitoring, baseUrl + "/")).isEqualTo(200);
    }

    @Test
    void loadEngine_usesTheApplicationsTrust() {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            HttpClientExecutionEngine engine = new HttpClientExecutionEngine(5, new ObjectMapper(), executor);
            List<StepExecutionSpec> steps = List.of(new StepExecutionSpec(UUID.randomUUID(), "tls", HttpMethod.GET, "/",
                    null, null, 200, null, null, null, null, null, null));
            LoadTestSpec load = new LoadTestSpec(1, 0, null, 1, 0, null, ThrottlePolicy.DEFAULT, 0);
            ScenarioExecutionOutcome ok = engine.execute(steps, baseUrl, ApplicationAuthSpec.none(), load, new RunningExecutionHandle(),
                    new VariableSources(List.of(), CsvDistribution.PER_VIRTUAL_USER, Map.of(), steps), NetworkProfile.of(app(pki.caPem)));
            assertThat(ok.stepOutcomes()).allSatisfy(o -> assertThat(o.success()).isTrue());

            ScenarioExecutionOutcome refused = engine.execute(steps, baseUrl, ApplicationAuthSpec.none(), load, new RunningExecutionHandle(),
                    new VariableSources(List.of(), CsvDistribution.PER_VIRTUAL_USER, Map.of(), steps), NetworkProfile.of(app(null)));
            assertThat(refused.stepOutcomes()).allSatisfy(o -> assertThat(o.success()).isFalse());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void theServersGlobalClientCertificate_isStillPresented_withTheApplicationsCa() throws Exception {
        Path clientKeystore = pki.createClientKeystore();
        HttpsServer mutual = pki.startServer(true);
        String url = "https://localhost:" + mutual.getAddress().getPort() + "/";
        String[] keys = {"javax.net.ssl.keyStore", "javax.net.ssl.keyStorePassword", "javax.net.ssl.keyStoreType"};
        String[] previous = new String[keys.length];
        for (int i = 0; i < keys.length; i++) previous[i] = System.getProperty(keys[i]);
        try {
            // Sans certificat client global : le serveur refuse la connexion.
            for (String key : keys) System.clearProperty(key);
            NetworkProfile withoutClientCert = NetworkProfile.of(app(pki.caPem));
            assertThatThrownBy(() -> get(withoutClientCert, url)).isInstanceOf(IOException.class);

            // Certificat client global (comme LOADPILOT_TLS_KEYSTORE_* via start.ps1) : toujours presente.
            System.setProperty("javax.net.ssl.keyStore", clientKeystore.toString());
            System.setProperty("javax.net.ssl.keyStorePassword", TestPki.PASSWORD);
            System.setProperty("javax.net.ssl.keyStoreType", "PKCS12");
            assertThat(get(NetworkProfile.of(app(pki.caPem)), url)).isEqualTo(200);
        } finally {
            for (int i = 0; i < keys.length; i++) {
                if (previous[i] == null) System.clearProperty(keys[i]); else System.setProperty(keys[i], previous[i]);
            }
            mutual.stop(0);
        }
    }

    @Test
    void unknownAuthority_givesAnActionableMessage() {
        assertThatThrownBy(() -> get(NetworkProfile.of(app(null)), baseUrl + "/"))
                .isInstanceOfSatisfying(IOException.class, e -> assertThat(NetworkErrors.describe(e, 5))
                        .contains("autorité de certification inconnue").contains("Réseau"));
    }

    @Test
    void pemValidation_refusesPrivateKeysAndGarbage_describesCertificates() {
        assertThat(TlsTrust.problem(null)).isNull();
        assertThat(TlsTrust.problem(pki.caPem)).isNull();
        assertThat(TlsTrust.problem(pki.caPem + "\n" + otherPki.caPem)).isNull();
        assertThat(TlsTrust.parse(pki.caPem + "\n" + otherPki.caPem)).hasSize(2);
        assertThat(TlsTrust.problem("-----BEGIN PRIVATE KEY-----\nMIIE\n-----END PRIVATE KEY-----")).contains("clé privée");
        assertThat(TlsTrust.problem("bonjour")).contains("format PEM attendu");
        assertThat(TlsTrust.problem("-----BEGIN CERTIFICATE-----\nZZZZ\n-----END CERTIFICATE-----")).contains("illisible");
        assertThat(TlsTrust.describe(pki.caPem)).singleElement().satisfies(info -> {
            assertThat(info.subject()).isEqualTo("CN=LoadPilot Test CA");
            assertThat(info.expired()).isFalse();
        });
        assertThatThrownBy(() -> NetworkProfile.direct().withTrustedAuthorities("bonjour")).isInstanceOf(IllegalArgumentException.class);
    }
}
