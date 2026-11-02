package com.loadpilot.backend.service.http;

import java.time.Duration;

/** Test de connectivite HTTP d'une application. */
public interface HttpAvailabilityChecker {

    /** Envoie une requete GET vers {@code url} ; un echec est renvoye dans le resultat, jamais par une exception. */
    HttpAvailabilityResult check(String url, Duration timeout);

    /** Variante avec en-tetes. */
    default HttpAvailabilityResult check(String url, java.util.Map<String, String> headers, Duration timeout) {
        return check(url, timeout);
    }

    /** Variante avec les reglages reseau de l'application. */
    default HttpAvailabilityResult check(String url, Duration timeout, NetworkProfile network) {
        return check(url, timeout);
    }

    /** Variante avec en-tetes et reglages reseau. */
    default HttpAvailabilityResult check(String url, java.util.Map<String, String> headers, Duration timeout, NetworkProfile network) {
        return check(url, headers, timeout);
    }
}
