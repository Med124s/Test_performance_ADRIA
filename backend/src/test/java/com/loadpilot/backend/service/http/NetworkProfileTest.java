package com.loadpilot.backend.service.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.enums.ProxyMode;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Reglages reseau d'une application : proxy global (comme avant), aucun, ou propre a l'application ;
 * validation identique aux scripts ; identifiant du proxy jamais dans les journaux.
 */
class NetworkProfileTest {

    private static final URI TARGET = URI.create("https://app.banque.local/api");

    private static String header(NetworkProfile profile, URI uri, ProxyAuthorization global) {
        return profile.applyTo(HttpRequest.newBuilder(uri), uri, global).build().headers().firstValue(ProxyAuthorization.HEADER).orElse(null);
    }

    private static Application app(ProxyMode mode, String host, Integer port, String user, String password) {
        return Application.builder().name("a").url("https://app.banque.local").proxyMode(mode).proxyHost(host).proxyPort(port)
                .proxyUsername(user).proxyPassword(password).build();
    }

    @Test
    void inherit_isExactlyTheServerProxy() {
        HttpClient client = NetworkProfile.inherit().configure(HttpClient.newBuilder()).build();
        try {
            assertThat(client.proxy()).as("selecteur par defaut de Java, comme avant").isEmpty();
        } finally {
            client.shutdownNow();
        }
        // Identifiant : celui du serveur (fourni par l'appelant), jamais un autre.
        assertThat(header(NetworkProfile.inherit(), TARGET, serverCredentials())).isEqualTo(basic("serveur", "pw"));
        assertThat(header(NetworkProfile.inherit(), TARGET, ProxyAuthorization.none())).isNull();
        assertThat(NetworkProfile.of(null)).isSameAs(NetworkProfile.inherit());
        assertThat(NetworkProfile.of(app(null, null, null, null, null))).isSameAs(NetworkProfile.inherit());
        assertThat(NetworkProfile.of(app(ProxyMode.INHERIT, null, null, null, null))).isSameAs(NetworkProfile.inherit());
    }

    @Test
    void none_neverUsesAProxy_evenWithAServerProxy() {
        HttpClient client = NetworkProfile.direct().configure(HttpClient.newBuilder()).build();
        try {
            assertThat(client.proxy()).isPresent();
            assertThat(client.proxy().get().select(TARGET)).containsExactly(Proxy.NO_PROXY);
        } finally {
            client.shutdownNow();
        }
        assertThat(header(NetworkProfile.direct(), TARGET, serverCredentials())).as("aucun identifiant envoye").isNull();
        assertThat(NetworkProfile.of(app(ProxyMode.NONE, null, null, null, null))).isSameAs(NetworkProfile.direct());
    }

    @Test
    void custom_usesTheApplicationProxy_andItsOwnCredentials() {
        NetworkProfile profile = NetworkProfile.of(app(ProxyMode.CUSTOM, " proxy.banque.local ", 8080, "agent", "s3cret"));
        assertThat(profile.mode()).isEqualTo(ProxyMode.CUSTOM);
        HttpClient client = profile.configure(HttpClient.newBuilder()).build();
        try {
            Proxy proxy = client.proxy().orElseThrow().select(TARGET).get(0);
            assertThat(proxy.type()).isEqualTo(Proxy.Type.HTTP);
            assertThat(((InetSocketAddress) proxy.address()).getHostString()).isEqualTo("proxy.banque.local");
            assertThat(((InetSocketAddress) proxy.address()).getPort()).isEqualTo(8080);
        } finally {
            client.shutdownNow();
        }
        // Identifiant de l'application, jamais celui du serveur.
        assertThat(header(profile, TARGET, serverCredentials())).isEqualTo(basic("agent", "s3cret"));
        // Sans identifiant : proxy de l'application, aucun en-tete.
        assertThat(header(NetworkProfile.custom("proxy", 3128, null, null), TARGET, serverCredentials())).isNull();
        // Journaux : jamais le mot de passe.
        assertThat(profile.toString()).isEqualTo("proxy proxy.banque.local:8080 (identifiant agent)").doesNotContain("s3cret");
    }

    @Test
    void validation_followsTheStartScripts() {
        assertThat(NetworkProfile.proxyProblem("proxy", 1, null)).isNull();
        assertThat(NetworkProfile.proxyProblem("proxy", 65535, "agent")).isNull();
        assertThat(NetworkProfile.proxyProblem(" ", 8080, null)).startsWith("Indiquez l'hôte du proxy");
        assertThat(NetworkProfile.proxyProblem("http://proxy", 8080, null)).startsWith("Hôte du proxy invalide");
        assertThat(NetworkProfile.proxyProblem("proxy/x", 8080, null)).startsWith("Hôte du proxy invalide");
        assertThat(NetworkProfile.proxyProblem("proxy", null, null)).startsWith("Indiquez le port du proxy");
        assertThat(NetworkProfile.proxyProblem("proxy", 0, null)).contains("entre 1 et 65535");
        assertThat(NetworkProfile.proxyProblem("proxy", 65536, null)).contains("entre 1 et 65535");
        assertThat(NetworkProfile.proxyProblem("proxy", 8080, "a:b")).contains("« : »");
        assertThatThrownBy(() -> NetworkProfile.custom("proxy", 70000, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void monitoring_followsTheApplication_unlessWithoutProxy() {
        Application custom = app(ProxyMode.CUSTOM, "proxy", 3128, null, null);
        assertThat(NetworkProfile.forMonitoring(custom).mode()).isEqualTo(ProxyMode.CUSTOM);
        custom.setMonitoringWithoutProxy(true);
        assertThat(NetworkProfile.forMonitoring(custom)).isSameAs(NetworkProfile.direct());
        assertThat(NetworkProfile.forMonitoring(app(null, null, null, null, null))).isSameAs(NetworkProfile.inherit());
    }

    /** Identifiant du serveur (LOADPILOT_HTTPS_PROXY_*), toutes les adresses passant par son proxy. */
    private static ProxyAuthorization serverCredentials() {
        return ProxyAuthorization.of("serveur", "pw", () -> ProxyAuthorization.fixed(
                new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy", 3128))));
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
