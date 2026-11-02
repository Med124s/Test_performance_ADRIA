package com.loadpilot.backend.service.execution;

import java.net.http.HttpHeaders;
import java.util.List;

/** Extraction d'une valeur dans un en-tete de reponse ("X-CSRF-Token"), ou dans un cookie ("Set-Cookie:NOM"). */
final class ResponseHeaderExtractor {

    static final String SET_COOKIE = "Set-Cookie";

    private ResponseHeaderExtractor() {
    }

    static String extract(HttpHeaders headers, String spec) {
        return evaluate(headers, spec).value();
    }

    /** Raison pour laquelle {@link #extract} ne trouve rien, ou null. */
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
