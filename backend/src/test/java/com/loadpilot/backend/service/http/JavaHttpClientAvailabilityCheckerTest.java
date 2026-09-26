package com.loadpilot.backend.service.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Preuve concrete que le checker effectue de VRAIES requetes HTTP - un vrai
 * serveur HTTP embarque (com.sun.net.httpserver, fourni par le JDK, aucune
 * dependance ajoutee) sert de cible, pour ne dependre ni d'Internet ni de
 * banking-test-api tout en restant un test 100% reel (pas de mock ici).
 */
class JavaHttpClientAvailabilityCheckerTest {

    private final JavaHttpClientAvailabilityChecker checker = new JavaHttpClientAvailabilityChecker();

    @Test
    void check_realReachableServer_returnsSuccessWithStatus200() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://localhost:" + server.getAddress().getPort() + "/";
            HttpAvailabilityResult result = checker.check(url, Duration.ofSeconds(3));

            assertThat(result.success()).isTrue();
            assertThat(result.httpStatus()).isEqualTo(200);
            assertThat(result.errorMessage()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void check_realServerReturningHttpError_returnsSuccessWithErrorHttpStatus() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://localhost:" + server.getAddress().getPort() + "/";
            HttpAvailabilityResult result = checker.check(url, Duration.ofSeconds(3));

            // "success" = une vraie reponse HTTP a ete recue, meme en erreur -
            // c'est a ApplicationService de la traduire en statut FAILED.
            assertThat(result.success()).isTrue();
            assertThat(result.httpStatus()).isEqualTo(500);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void check_realServerReturning404_returnsSuccessWithErrorHttpStatus() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://localhost:" + server.getAddress().getPort() + "/";
            HttpAvailabilityResult result = checker.check(url, Duration.ofSeconds(3));

            assertThat(result.success()).isTrue();
            assertThat(result.httpStatus()).isEqualTo(404);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void check_slowServer_timesOut_returnsFailureWithoutHttpStatus() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }));
        server.start();
        try {
            String url = "http://localhost:" + server.getAddress().getPort() + "/";
            HttpAvailabilityResult result = checker.check(url, Duration.ofMillis(300));

            assertThat(result.success()).isFalse();
            assertThat(result.httpStatus()).isNull();
            assertThat(result.errorMessage()).isNotBlank();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void check_connectionRefused_returnsFailureWithoutHttpStatus() throws IOException {
        // Port libere juste avant l'appel : personne n'ecoute dessus, donc
        // une vraie connexion refusee (pas simulee) - preuve d'un echec
        // technique reel, sans dependre d'un timeout lent ni d'Internet.
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }

        HttpAvailabilityResult result = checker.check("http://localhost:" + freePort + "/", Duration.ofSeconds(2));

        assertThat(result.success()).isFalse();
        assertThat(result.httpStatus()).isNull();
        assertThat(result.errorMessage()).isNotBlank();
    }
}
