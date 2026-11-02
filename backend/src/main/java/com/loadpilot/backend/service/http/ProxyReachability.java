package com.loadpilot.backend.service.http;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.function.Supplier;

/** Verifie qu'un proxy accepte une connexion TCP, avant le test de l'application. */
public final class ProxyReachability {

    /** Delai de connexion au proxy. */
    public static final Duration TIMEOUT = Duration.ofSeconds(3);

    private ProxyReachability() {
    }

    /** null si le proxy accepte la connexion, sinon la raison (message en francais, sans secret). */
    public static String problem(String host, Integer port) {
        return problem(host, port, TIMEOUT, Socket::new);
    }

    /** "sockets" : fabrique de sockets (tests). */
    static String problem(String host, Integer port, Duration timeout, Supplier<Socket> sockets) {
        String invalid = NetworkProfile.proxyProblem(host, port, null);
        if (invalid != null) return invalid;
        InetSocketAddress address = new InetSocketAddress(host.trim(), port);
        if (address.isUnresolved()) return "hôte du proxy introuvable (DNS)";
        try (Socket socket = sockets.get()) {
            socket.connect(address, (int) timeout.toMillis());
            return null;
        } catch (UnknownHostException e) {
            return "hôte du proxy introuvable (DNS)";
        } catch (SocketTimeoutException e) {
            return "le proxy ne répond pas (délai de " + timeout.toSeconds() + " s dépassé)";
        } catch (ConnectException e) {
            return "connexion refusée par le proxy (port fermé)";
        } catch (NoRouteToHostException e) {
            return "hôte du proxy injoignable (aucune route réseau)";
        } catch (IOException e) {
            return "erreur réseau vers le proxy (" + e.getClass().getSimpleName() + ")";
        }
    }

    /** Message complet, ex. "Proxy injoignable (proxy.banque.local:8080) : connexion refusee". */
    public static String message(String host, Integer port, String problem) {
        return "Proxy injoignable (" + (host == null ? "" : host.trim()) + ":" + (port == null ? "" : port) + ") : " + problem + ".";
    }
}
