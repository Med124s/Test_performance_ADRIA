package com.loadpilot.backend.service.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Proxy joignable ? Format verifie avant toute tentative, connexion directe sur l'hote et le port du
 * proxy, raison precise de l'echec, socket toujours ferme.
 */
class ProxyReachabilityTest {

    @Test
    void reachableProxy_ok() throws IOException {
        try (ServerSocket proxy = new ServerSocket(0)) {
            assertThat(ProxyReachability.problem("localhost", proxy.getLocalPort())).isNull();
        }
    }

    @Test
    void invalidHostOrPort_isRejectedBeforeAnyNetworkAttempt() {
        AtomicBoolean socketCreated = new AtomicBoolean();
        java.util.function.Supplier<Socket> sockets = () -> {
            socketCreated.set(true);
            return new Socket();
        };
        assertThat(ProxyReachability.problem(" ", 8080, Duration.ofSeconds(1), sockets)).startsWith("Indiquez l'hôte du proxy");
        assertThat(ProxyReachability.problem(null, 8080, Duration.ofSeconds(1), sockets)).startsWith("Indiquez l'hôte du proxy");
        assertThat(ProxyReachability.problem("http://proxy", 8080, Duration.ofSeconds(1), sockets)).startsWith("Hôte du proxy invalide");
        assertThat(ProxyReachability.problem("proxy", null, Duration.ofSeconds(1), sockets)).startsWith("Indiquez le port du proxy");
        assertThat(ProxyReachability.problem("proxy", 0, Duration.ofSeconds(1), sockets)).contains("entre 1 et 65535");
        assertThat(ProxyReachability.problem("proxy", 70000, Duration.ofSeconds(1), sockets)).contains("entre 1 et 65535");
        assertThat(socketCreated).as("aucune tentative reseau").isFalse();
    }

    @Test
    void unknownHost_hostNotFound() {
        assertThat(ProxyReachability.problem("proxy.inexistant.invalid", 3128)).isEqualTo("hôte du proxy introuvable (DNS)");
    }

    @Test
    void closedPort_connectionRefused() throws IOException {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        assertThat(ProxyReachability.problem("localhost", closedPort)).isEqualTo("connexion refusée par le proxy (port fermé)");
    }

    @Test
    void noAnswer_timeout_andTheSocketIsAlwaysClosed() {
        AtomicBoolean closed = new AtomicBoolean();
        Socket silent = new Socket() {
            @Override
            public void connect(SocketAddress endpoint, int timeout) throws IOException {
                assertThat(timeout).as("delai explicite").isEqualTo(3000);
                throw new SocketTimeoutException("connect timed out");
            }

            @Override
            public synchronized void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };
        assertThat(ProxyReachability.problem("localhost", 3128, Duration.ofSeconds(3), () -> silent))
                .isEqualTo("le proxy ne répond pas (délai de 3 s dépassé)");
        assertThat(closed).as("socket ferme meme en cas d'echec").isTrue();
        assertThat(ProxyReachability.TIMEOUT).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void message_namesTheProxy_andOnlyCustomProxiesAreChecked() {
        assertThat(ProxyReachability.message("proxy.banque.local", 8080, "connexion refusée par le proxy (port fermé)"))
                .isEqualTo("Proxy injoignable (proxy.banque.local:8080) : connexion refusée par le proxy (port fermé).");
        assertThat(NetworkProfile.inherit().proxyUnreachable()).isNull();
        assertThat(NetworkProfile.direct().proxyUnreachable()).isNull();
        assertThat(NetworkProfile.custom("proxy.inexistant.invalid", 3128, null, null).proxyUnreachable())
                .isEqualTo("Proxy injoignable (proxy.inexistant.invalid:3128) : hôte du proxy introuvable (DNS).");
    }
}
