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

/**
 * Le proxy d'une application est-il joignable ? Verifie AVANT le test de l'application (bouton
 * « Tester », « Tester le monitoring ») : une connexion TCP directe sur l'hote et le port DU PROXY,
 * jamais sur l'application - un echec designe alors le proxy, et non « Application injoignable ».
 *
 * Ne prouve pas que le service ecoutant sur ce port est un proxy : cela, seul le test de
 * l'application a travers lui le montre (il suit juste apres).
 */
public final class ProxyReachability {

    /** Delai de connexion au proxy : court, un proxy d'entreprise repond en quelques millisecondes. */
    public static final Duration TIMEOUT = Duration.ofSeconds(3);

    private ProxyReachability() {
    }

    /** null si le proxy accepte la connexion, sinon la raison (message en francais, sans secret). */
    public static String problem(String host, Integer port) {
        return problem(host, port, TIMEOUT, Socket::new);
    }

    /** "sockets" : fabrique de socket (tests : delai depasse simule, fermeture verifiee). */
    static String problem(String host, Integer port, Duration timeout, Supplier<Socket> sockets) {
        // Format d'abord : jamais de tentative reseau sur une saisie invalide.
        String invalid = NetworkProfile.proxyProblem(host, port, null);
        if (invalid != null) return invalid;
        InetSocketAddress address = new InetSocketAddress(host.trim(), port); // resolution DNS
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

    /** Message complet : « Proxy injoignable (proxy.banque.local:8080) : connexion refusée… ». */
    public static String message(String host, Integer port, String problem) {
        return "Proxy injoignable (" + (host == null ? "" : host.trim()) + ":" + (port == null ? "" : port) + ") : " + problem + ".";
    }
}
