package com.loadpilot.backend.service.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Master prompt final (Lot B) — extraction REELLE d'une valeur depuis un
 * corps de reponse JSON, pour la capture de variable dynamique (voir
 * Step.captureVariableName/captureJsonPath, HttpClientExecutionEngine) et le
 * jeton de la Connexion automatique (ApplicationAuthenticator).
 *
 * Sous-ensemble VOLONTAIREMENT SIMPLE, jamais un vrai moteur JSONPath
 * complet (pas de filtres/wildcards/fonctions/index negatifs) : navigation
 * par segments separes par des points dans l'arbre JSON (Jackson JsonNode,
 * DEJA une dependance reelle de ce projet — voir parseHeaders — donc aucune
 * nouvelle dependance ajoutee pour ce besoin), chaque segment pouvant etre
 * suivi d'un ou plusieurs index de tableau entre crochets (entier >= 0). Le
 * prefixe "$." ou "$" est accepte et ignore par confort (convention JSONPath
 * usuelle), mais n'active aucune semantique JSONPath supplementaire.
 * Exemples supportes : "token", "$.token", "data.token", "$.data.token",
 * "data.accounts[0].id", "items[2].name", "data[0][1].value", "$[0].id"
 * (reponse dont la racine est un tableau).
 *
 * Ne leve JAMAIS d'exception vers l'appelant : une reponse non-JSON, un
 * chemin introuvable ou invalide, ou un chemin/JSON null renvoient simplement
 * null (aucune variable produite, jamais une erreur d'execution — voir
 * politique documentee sur Step.captureJsonPath). {@link #explain} donne la
 * raison de cet echec, pour les messages affiches a l'utilisateur.
 */
final class JsonPathExtractor {

    /** Un segment : nom de champ (eventuellement vide), puis zero ou plusieurs index "[n]" (9 chiffres au plus). */
    private static final Pattern SEGMENT = Pattern.compile("([^\\[\\]]*)((?:\\[\\d{1,9}])*)");
    private static final Pattern INDEX = Pattern.compile("\\[(\\d{1,9})]");

    private JsonPathExtractor() {
    }

    static String extract(ObjectMapper objectMapper, String json, String path) {
        return evaluate(objectMapper, json, path, false).value();
    }

    /**
     * Comme {@link #extract}, mais un objet ou un tableau est rendu en JSON compact (ex. ["a","b"])
     * au lieu d'une chaine vide : utilise par l'assertion JSON (voir StepChecks).
     */
    static String extractForAssertion(ObjectMapper objectMapper, String json, String path) {
        return evaluate(objectMapper, json, path, true).value();
    }

    /** true si le chemin respecte la syntaxe acceptee (index entre crochets, entiers positifs). */
    static boolean isValidPath(String path) {
        if (path == null || path.isBlank()) return false;
        return parse(stripRoot(path.trim())) != null;
    }

    /**
     * Raison lisible pour laquelle {@link #extract} ne trouve rien (ex. « index 3 hors limites pour
     * data.accounts (tableau de 2 éléments) »), en minuscule et sans point final, a placer apres le
     * chemin dans le message de l'appelant. null si une valeur est trouvee, ou si le JSON ou le chemin
     * sont vides.
     */
    static String explain(ObjectMapper objectMapper, String json, String path) {
        return evaluate(objectMapper, json, path, false).reason();
    }

    /** Valeur trouvee, ou raison de l'absence (jamais les deux). */
    private record Result(String value, String reason) {
        static Result failure(String reason) {
            return new Result(null, reason);
        }
    }

    private static Result evaluate(ObjectMapper objectMapper, String json, String path, boolean containersAsJson) {
        if (json == null || json.isBlank() || path == null || path.isBlank()) {
            return new Result(null, null);
        }
        try {
            String cleanPath = stripRoot(path);
            List<Object> tokens = parse(cleanPath);
            if (tokens == null) {
                return Result.failure("chemin invalide : un index de tableau s'écrit entre crochets avec un entier positif, ex. items[0]");
            }
            JsonNode node = objectMapper.readTree(json);
            if (node == null || node.isMissingNode()) {
                return Result.failure("réponse non JSON");
            }
            // Chemin deja parcouru, pour situer l'erreur (ex. "data.accounts[0]").
            StringBuilder at = new StringBuilder();
            for (Object token : tokens) {
                if (token instanceof String field) {
                    if (!node.isObject()) {
                        return Result.failure(node.isArray()
                                ? label(at) + " est un tableau : indiquez un index (ex. " + (at.isEmpty() ? "$" : at) + "[0])"
                                : label(at) + " n'est pas un objet (" + kind(node) + ")");
                    }
                    JsonNode child = node.get(field);
                    if (child == null) {
                        return Result.failure("aucun champ « " + field + " » " + (at.isEmpty() ? "à la racine" : "dans " + at));
                    }
                    if (!at.isEmpty()) at.append('.');
                    at.append(field);
                    node = child;
                } else {
                    int index = (Integer) token;
                    if (!node.isArray()) {
                        return Result.failure(label(at) + " n'est pas un tableau (" + kind(node) + ")");
                    }
                    if (index >= node.size()) {
                        return Result.failure("index " + index + " hors limites pour " + label(at) + " ("
                                + (node.isEmpty() ? "tableau vide" : "tableau de " + node.size() + " élément" + (node.size() > 1 ? "s" : "")) + ")");
                    }
                    at.append('[').append(index).append(']');
                    node = node.get(index);
                }
            }
            if (node.isNull()) {
                return Result.failure("valeur nulle à " + label(at));
            }
            return new Result(containersAsJson && node.isContainerNode() ? node.toString() : node.asText(), null);
        } catch (Exception e) {
            return Result.failure("réponse non JSON");
        }
    }

    /**
     * Decoupe le chemin en noms de champ (String) et index de tableau (Integer), dans l'ordre.
     * Segments vides ignores (comme avant : "data..token" == "data.token"). null = chemin invalide
     * (crochet non ferme, index non entier ou negatif, crochet au milieu d'un nom).
     */
    private static List<Object> parse(String path) {
        List<Object> tokens = new ArrayList<>();
        for (String segment : path.split("\\.")) {
            if (segment.isBlank()) continue;
            Matcher m = SEGMENT.matcher(segment);
            if (!m.matches()) return null;
            if (!m.group(1).isEmpty()) tokens.add(m.group(1));
            Matcher index = INDEX.matcher(m.group(2));
            while (index.find()) {
                tokens.add(Integer.parseInt(index.group(1)));
            }
        }
        return tokens;
    }

    private static String stripRoot(String path) {
        return path.startsWith("$.") ? path.substring(2) : path.startsWith("$") ? path.substring(1) : path;
    }

    private static String label(StringBuilder at) {
        return at.isEmpty() ? "la racine" : at.toString();
    }

    private static String kind(JsonNode node) {
        if (node.isObject()) return "objet";
        if (node.isArray()) return "tableau";
        if (node.isNull()) return "valeur nulle";
        return "valeur simple";
    }
}
