package com.loadpilot.backend.service.execution;

import java.net.http.HttpHeaders;
import java.util.List;

/**
 * Extraction d'une valeur depuis un EN-TETE de la reponse HTTP (capture de source HEADER, voir
 * CaptureSource), pendant de JsonPathExtractor pour le corps JSON.
 *
 * Le « chemin » est simplement le nom de l'en-tete (ex. "X-CSRF-Token"), recherche sans tenir
 * compte de la casse comme tout en-tete HTTP. Regles :
 *   - plusieurs valeurs pour le meme en-tete : la PREMIERE est retenue, telle quelle (espaces de
 *     debut et de fin retires) ;
 *   - "Set-Cookie:NOM" : valeur du cookie NOM (ce qui suit "NOM=" jusqu'au premier ";", sans les
 *     guillemets eventuels), cherchee dans TOUS les en-tetes Set-Cookie (un par cookie) ; le nom du
 *     cookie respecte la casse. Aucune gestion de cookie (expiration, domaine, renvoi automatique).
 *
 * Ne leve JAMAIS d'exception : en-tete absent ou vide = null ; {@link #explain} donne la raison
 * pour les messages affiches a l'utilisateur (meme style que JsonPathExtractor#explain).
 */
final class ResponseHeaderExtractor {

    static final String SET_COOKIE = "Set-Cookie";

    private ResponseHeaderExtractor() {
    }

    static String extract(HttpHeaders headers, String spec) {
        return evaluate(headers, spec).value();
    }

    /**
     * Raison lisible pour laquelle {@link #extract} ne trouve rien (ex. « en-tête « X-CSRF-Token »
     * absent de la réponse »), en minuscule et sans point final. null si une valeur est trouvee, ou si
     * les en-tetes ou le nom sont vides.
     */
    static String explain(HttpHeaders headers, String spec) {
        return evaluate(headers, spec).reason();
    }

    private record Result(String value, String reason) {
        static Result failure(String reason) {
            return new Result(null, reason);
        }
    }

    private static Result evaluate(HttpHeaders headers, String spec) {
        if (headers == null || spec == null || spec.isBlank()) {
            return new Result(null, null);
        }
        String trimmed = spec.trim();
        int colon = trimmed.indexOf(':');
        String name = colon < 0 ? trimmed : trimmed.substring(0, colon).trim();
        String cookie = colon < 0 ? null : trimmed.substring(colon + 1).trim();
        if (name.isEmpty() || (cookie != null && (cookie.isEmpty() || !name.equalsIgnoreCase(SET_COOKIE)))) {
            return Result.failure("en-tête invalide : indiquez un nom d'en-tête (ex. X-CSRF-Token), ou Set-Cookie:NOM pour un cookie");
        }
        // HttpHeaders : recherche insensible a la casse.
        List<String> values = headers.allValues(name);
        if (values.isEmpty()) {
            return Result.failure("en-tête « " + name + " » absent de la réponse");
        }
        if (cookie == null) {
            String first = values.get(0).trim();
            return first.isEmpty() ? Result.failure("en-tête « " + name + " » vide") : new Result(first, null);
        }
        for (String setCookie : values) {
            String pair = setCookie.split(";", 2)[0];
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).trim().equals(cookie)) {
                String value = pair.substring(equals + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                return value.isEmpty() ? Result.failure("cookie « " + cookie + " » vide") : new Result(value, null);
            }
        }
        return Result.failure("aucun cookie « " + cookie + " » dans les en-têtes Set-Cookie de la réponse");
    }
}
