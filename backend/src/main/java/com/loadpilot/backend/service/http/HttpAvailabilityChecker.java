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
}
