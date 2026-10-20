package com.loadpilot.backend.service.http;

import java.net.http.HttpClient;

/**
 * Fermeture des clients java.net.http.HttpClient crees pour un usage ponctuel. Chaque client demarre
 * un thread systeme (selecteur) et garde ses descripteurs et connexions jusqu'a sa fermeture - sans
 * fermeture, jusqu'au passage du GC (pertinent sur un serveur Linux avec un ulimit -n bas).
 */
public final class HttpClients {

    private HttpClients() {
    }

    /**
     * shutdownNow() : immediat, n'attend aucun echange (close() attendrait la fin des echanges encore
     * actifs). A appeler une fois l'appel termine, par le thread qui l'a fait. null accepte ; jamais
     * d'exception, la fermeture ne doit pas masquer le resultat de l'appel.
     */
    public static void shutdownQuietly(HttpClient client) {
        if (client == null) return;
        try {
            client.shutdownNow();
        } catch (RuntimeException e) {
            // Rien a faire : le client sera de toute facon libere par le GC.
        }
    }
}
