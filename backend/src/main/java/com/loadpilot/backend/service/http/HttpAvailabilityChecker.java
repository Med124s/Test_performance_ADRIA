package com.loadpilot.backend.service.http;

import java.time.Duration;

/**
 * Abstraction du test de disponibilite HTTP reel - point d'extension
 * unique et testable (voir JavaHttpClientAvailabilityChecker, l'unique
 * implementation reelle, et son mock dans les tests de service/controller).
 */
public interface HttpAvailabilityChecker {

    /** Envoie une vraie requete HTTP GET vers {@code url}. Ne leve jamais :
     * tout echec (reseau, timeout, URL invalide) est reporte dans le
     * resultat, jamais via une exception remontee a l'appelant. */
    HttpAvailabilityResult check(String url, Duration timeout);

    /** Variante avec en-tetes (authentification de l'Application). Par
     * defaut, ignore les en-tetes (implementations de test). */
    default HttpAvailabilityResult check(String url, java.util.Map<String, String> headers, Duration timeout) {
        return check(url, timeout);
    }

    /** Sans en-tete, avec les reglages reseau de l'application (proxy). Par defaut, les ignore (implementations de test). */
    default HttpAvailabilityResult check(String url, Duration timeout, NetworkProfile network) {
        return check(url, timeout);
    }

    /** Variante complete : reglages reseau de l'application (proxy). Par defaut, les ignore (implementations de test). */
    default HttpAvailabilityResult check(String url, java.util.Map<String, String> headers, Duration timeout, NetworkProfile network) {
        return check(url, headers, timeout);
    }
}
