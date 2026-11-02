package com.loadpilot.backend.service.http;

import java.net.http.HttpClient;

/** Fermeture des clients HttpClient crees pour un usage ponctuel. */
public final class HttpClients {

    private HttpClients() {
    }

    /** Ferme le client immediatement ; null accepte, jamais d'exception. */
    public static void shutdownQuietly(HttpClient client) {
        if (client == null) return;
        try {
            client.shutdownNow();
        } catch (RuntimeException e) {
            // Fermeture impossible : ignoree.
        }
    }
}
