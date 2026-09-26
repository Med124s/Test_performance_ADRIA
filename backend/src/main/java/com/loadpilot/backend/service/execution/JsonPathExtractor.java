package com.loadpilot.backend.service.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Master prompt final (Lot B) — extraction REELLE d'une valeur depuis un
 * corps de reponse JSON, pour la capture de variable dynamique (voir
 * Step.captureVariableName/captureJsonPath, HttpClientExecutionEngine).
 *
 * Sous-ensemble VOLONTAIREMENT SIMPLE, jamais un vrai moteur JSONPath
 * complet (pas de tableaux/filtres/wildcards/fonctions) : navigation par
 * segments separes par des points dans l'arbre JSON (Jackson JsonNode,
 * DEJA une dependance reelle de ce projet — voir parseHeaders — donc aucune
 * nouvelle dependance ajoutee pour ce besoin). Le prefixe "$." ou "$" est
 * accepte et ignore par confort (convention JSONPath usuelle), mais
 * n'active aucune semantique JSONPath supplementaire. Exemples supportes :
 * "token", "$.token", "data.token", "$.data.token".
 *
 * Ne leve JAMAIS d'exception vers l'appelant : une reponse non-JSON, un
 * chemin introuvable, ou un chemin/JSON null renvoient simplement null
 * (aucune variable produite, jamais une erreur d'execution — voir
 * politique documentee sur Step.captureJsonPath).
 */
final class JsonPathExtractor {

    private JsonPathExtractor() {
    }

    static String extract(ObjectMapper objectMapper, String json, String path) {
        if (json == null || json.isBlank() || path == null || path.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            String cleanPath = path.startsWith("$.") ? path.substring(2)
                    : path.startsWith("$") ? path.substring(1)
                    : path;
            for (String segment : cleanPath.split("\\.")) {
                if (segment.isBlank()) continue;
                node = node.path(segment);
            }
            return (node.isMissingNode() || node.isNull()) ? null : node.asText();
        } catch (Exception e) {
            return null;
        }
    }
}
