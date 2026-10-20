package com.loadpilot.backend.service.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.enums.JsonAssertionOperator;

/**
 * Controles optionnels d'une etape, en plus du code HTTP attendu et du « contenu attendu » (jamais a
 * leur place) : temps de reponse maximal, et assertion JSON simple « le champ X egale / contient Y ».
 * Le champ est lu par JsonPathExtractor (meme syntaxe que la capture). Utilise a l'identique par le
 * moteur de charge et par « Tester » (StepTestRunner). Messages en francais, null = controle reussi.
 */
public final class StepChecks {

    private static final int SHOWN_VALUE_MAX = 100;

    private StepChecks() {
    }

    /** true si un temps de reponse maximal est configure. */
    public static boolean hasMaxResponseTime(Integer maxResponseTimeMs) {
        return maxResponseTimeMs != null && maxResponseTimeMs > 0;
    }

    /** true si une assertion JSON est configuree (le champ suffit : comparaison EQUALS par defaut). */
    public static boolean hasJsonAssertion(String path) {
        return path != null && !path.isBlank();
    }

    /** Message d'echec si le temps de reponse depasse le maximum, sinon null (et null sans maximum). */
    public static String responseTimeProblem(Integer maxResponseTimeMs, long elapsedMs) {
        if (!hasMaxResponseTime(maxResponseTimeMs) || elapsedMs <= maxResponseTimeMs) return null;
        return "Temps de réponse trop long : " + elapsedMs + " ms (maximum autorisé " + maxResponseTimeMs + " ms)";
    }

    /** Message d'echec de l'assertion JSON, ou null si elle est verifiee (ou absente). */
    public static String jsonAssertionProblem(ObjectMapper objectMapper, String body, String path,
            JsonAssertionOperator operator, String expected) {
        if (!hasJsonAssertion(path)) return null;
        String field = path.trim();
        String want = expected == null ? "" : expected;
        String actual = JsonPathExtractor.extractForAssertion(objectMapper, body, field);
        if (actual == null) {
            String reason = JsonPathExtractor.explain(objectMapper, body, field);
            return "Assertion JSON : champ " + field + " introuvable"
                    + (reason != null ? " (" + reason + ")" : body == null || body.isBlank() ? " (réponse vide)" : "");
        }
        if (operator == JsonAssertionOperator.CONTAINS) {
            return actual.contains(want) ? null
                    : "Assertion JSON : " + field + " ne contient pas « " + shown(want) + " » (valeur « " + shown(actual) + " »)";
        }
        return equalsValue(objectMapper, actual, want) ? null
                : "Assertion JSON : " + field + " vaut « " + shown(actual) + " », attendu « " + shown(want) + " »";
    }

    /** Probleme de configuration (pour StepRules), ou null : syntaxe du champ seulement. */
    public static String configurationProblem(String path, JsonAssertionOperator operator, String expected) {
        boolean hasPath = hasJsonAssertion(path);
        if (!hasPath) {
            return (expected != null && !expected.isEmpty()) || operator != null
                    ? "Assertion JSON : indiquez le champ à vérifier (ex. $.status)." : null;
        }
        if (!JsonPathExtractor.isValidPath(path)) {
            return "Assertion JSON : champ invalide (ex. $.status ou data.items[0].id).";
        }
        if (operator == JsonAssertionOperator.CONTAINS && (expected == null || expected.isEmpty())) {
            return "Assertion JSON : indiquez le texte que le champ doit contenir.";
        }
        return null;
    }

    /** Egalite texte ; pour un objet ou un tableau, egalite JSON (espaces et ordre des cles ignores). */
    private static boolean equalsValue(ObjectMapper objectMapper, String actual, String expected) {
        if (actual.equals(expected)) return true;
        String trimmed = expected.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return false;
        try {
            JsonNode want = objectMapper.readTree(trimmed);
            JsonNode got = objectMapper.readTree(actual);
            return want != null && want.equals(got);
        } catch (Exception e) {
            return false;
        }
    }

    private static String shown(String value) {
        return value.length() > SHOWN_VALUE_MAX ? value.substring(0, SHOWN_VALUE_MAX) + "…" : value;
    }
}
