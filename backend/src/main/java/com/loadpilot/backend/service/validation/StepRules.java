package com.loadpilot.backend.service.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.dto.request.StepRequest;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Regles de validation d'une etape au-dela des annotations de StepRequest - IDENTIQUES a
 * validateStep() du frontend (pages/scenario-wizard/wizardModel.ts) : URL relative ou
 * http(s)://hote, en-tetes en objet JSON avec des noms valides, corps JSON valide quand il
 * commence par { ou [ (les ${variables} etant neutralisees), capture complete.
 */
public final class StepRules {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern ABSOLUTE_URL = Pattern.compile("^https?://[^/?#\\s]+.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Pattern VARIABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{[^}]+}");

    private StepRules() {
    }

    /** Premier probleme trouve (message lisible), ou null si l'etape est valide. */
    public static String validate(StepRequest request) {
        String problem = validate(request.url(), request.headers(), request.body(), request.captureVariableName(),
                request.captureJsonPath());
        return problem != null ? problem : validateParams(request.pathParams(), request.queryParams());
    }

    /**
     * Variables de chemin : objet JSON {"nom": "valeur"} (noms de variable valides, valeurs texte).
     * Parametres de requete : tableau JSON [{"key": "...", "value": "...", "enabled": true}], 50 au maximum.
     */
    public static String validateParams(String pathParams, String queryParams) {
        if (pathParams != null && !pathParams.isBlank()) {
            JsonNode node;
            try {
                node = MAPPER.readTree(pathParams);
            } catch (Exception e) {
                return "Variables de chemin : JSON illisible.";
            }
            if (node == null || !node.isObject()) return "Variables de chemin : un objet {\"nom\": \"valeur\"} est attendu.";
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                if (!VARIABLE_NAME.matcher(entry.getKey()).matches()) {
                    return "Variable de chemin « " + entry.getKey() + " » : lettres, chiffres et _ uniquement.";
                }
                if (!entry.getValue().isValueNode() || entry.getValue().isNull()) {
                    return "Variable de chemin « " + entry.getKey() + " » : la valeur doit être un texte.";
                }
            }
        }
        if (queryParams != null && !queryParams.isBlank()) {
            JsonNode node;
            try {
                node = MAPPER.readTree(queryParams);
            } catch (Exception e) {
                return "Paramètres de requête : JSON illisible.";
            }
            if (node == null || !node.isArray()) return "Paramètres de requête : un tableau [{\"key\", \"value\", \"enabled\"}] est attendu.";
            if (node.size() > 50) return "50 paramètres de requête au maximum.";
            for (JsonNode param : node) {
                JsonNode key = param.get("key");
                if (!param.isObject() || key == null || !key.isTextual() || key.asText().isBlank()) {
                    return "Paramètres de requête : chaque paramètre doit avoir un nom.";
                }
                JsonNode value = param.get("value");
                if (value != null && !value.isNull() && !value.isValueNode()) {
                    return "Paramètre « " + key.asText() + " » : la valeur doit être un texte.";
                }
            }
        }
        return null;
    }

    public static String validate(String url, String headers, String body, String captureName, String capturePath) {
        String trimmedUrl = url == null ? "" : url.trim();
        if (trimmedUrl.contains("://") && !ABSOLUTE_URL.matcher(trimmedUrl).matches()) {
            return "URL invalide : utilisez un chemin relatif (ex. /api/ressource) ou une adresse http(s)://hôte.";
        }
        if (headers != null && !headers.isBlank()) {
            JsonNode node;
            try {
                node = MAPPER.readTree(headers);
            } catch (Exception e) {
                return "En-têtes invalides : JSON illisible.";
            }
            if (node == null || !node.isObject()) {
                return "En-têtes invalides : un objet JSON {\"Nom\": \"valeur\"} est attendu.";
            }
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> header = it.next();
                if (!HEADER_NAME.matcher(header.getKey()).matches()) {
                    return "Nom d'en-tête invalide « " + header.getKey() + " » : pas d'espace ni de caractère spécial.";
                }
                if (!header.getValue().isTextual()) {
                    return "En-tête « " + header.getKey() + " » : la valeur doit être un texte.";
                }
            }
        }
        if (body != null) {
            String trimmed = body.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                try {
                    MAPPER.readTree(VARIABLE.matcher(trimmed).replaceAll("0"));
                } catch (Exception e) {
                    return "Corps JSON invalide : vérifiez les guillemets, virgules et accolades.";
                }
            }
        }
        boolean hasName = captureName != null && !captureName.isBlank();
        boolean hasPath = capturePath != null && !capturePath.isBlank();
        if (hasName && !hasPath) return "Capture : indiquez où lire la valeur (ex. $.token).";
        if (hasPath && !hasName) return "Capture : donnez un nom à la variable (ex. token).";
        if (hasName && !VARIABLE_NAME.matcher(captureName.trim()).matches()) {
            return "Capture : nom de variable invalide (lettres, chiffres et _ uniquement).";
        }
        return null;
    }
}
