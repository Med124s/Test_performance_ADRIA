package com.loadpilot.backend.service.execution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Construit la requete REELLE d'une etape a partir de ses gabarits (URL, variables de chemin,
 * parametres de requete, en-tetes, corps) et d'une source de variables (VariableContext).
 *
 * - URL : variables de chemin ":nom" (apres un "/") et "{nom}", valeur = path_params[nom]
 *   (texte fixe et/ou ${variable}), par defaut ${nom} ; ${variable} directement dans le chemin
 *   ou dans la query de l'URL ; parametres de requete actives de query_params ajoutes a la fin.
 *   Toute valeur INJECTEE est encodee (segment de chemin ou composant de query) ; le texte
 *   saisi tel quel dans l'URL n'est jamais re-encode. Une seule passe : une valeur injectee
 *   n'est jamais re-interpretee comme gabarit.
 * - En-tetes et corps : ${variable} remplacees telles quelles (aucun encodage URL).
 * - Variable introuvable : listee dans "missing" ; l'appelant echoue AVANT tout envoi.
 */
public final class RequestBuilder {

    private static final Pattern URL_TOKEN = Pattern.compile(
            "\\$\\{([^}]+)}|(?<=/):([A-Za-z_][A-Za-z0-9_]*)|(?<!\\$)\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern ABSOLUTE = Pattern.compile("^(https?://[^/?#]*)(.*)$", Pattern.CASE_INSENSITIVE);

    private RequestBuilder() {
    }

    public record QueryParam(String key, String value, Boolean enabled) {
    }

    public record BuiltRequest(String url, Map<String, String> headers, String body, Set<String> missing,
            List<String> warnings) {
    }

    public static BuiltRequest build(StepExecutionSpec step, String applicationBaseUrl, Function<String, String> lookup,
            ObjectMapper objectMapper) {
        Set<String> missing = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        Function<String, String> resolve = name -> {
            String value = lookup.apply(name.trim());
            if (value == null) missing.add(name.trim());
            return value;
        };
        Map<String, String> pathParams = parsePathParams(step.pathParams(), objectMapper);

        // 1. URL : prefixe absolu (schema + hote) non encode, chemin, query, fragment.
        String template = step.url() == null ? "" : step.url().trim();
        String prefix = "";
        Matcher absolute = ABSOLUTE.matcher(template);
        if (absolute.matches()) {
            prefix = substitute(absolute.group(1), resolve, Function.identity());
            template = absolute.group(2);
        }
        String fragment = "";
        int hash = template.indexOf('#');
        if (hash >= 0) {
            fragment = template.substring(hash);
            template = template.substring(0, hash);
        }
        int question = template.indexOf('?');
        String pathTemplate = question >= 0 ? template.substring(0, question) : template;
        String queryTemplate = question >= 0 ? template.substring(question + 1) : "";

        String path = replaceUrlTokens(pathTemplate, name -> {
            String raw = pathParams.containsKey(name) ? pathParams.get(name) : "${" + name + "}";
            return substitute(raw, resolve, Function.identity());
        }, resolve, RequestBuilder::encodePathSegment);

        List<String> query = new ArrayList<>();
        if (!queryTemplate.isEmpty()) {
            for (String pair : queryTemplate.split("&")) {
                if (!pair.isEmpty()) query.add(substitute(pair, resolve, RequestBuilder::encodeQueryComponent));
            }
        }
        for (QueryParam param : parseQueryParams(step.queryParams(), objectMapper)) {
            if (param.key() == null || param.key().isBlank() || Boolean.FALSE.equals(param.enabled())) continue;
            String key = substitute(param.key().trim(), resolve, Function.identity());
            String value = substitute(param.value() == null ? "" : param.value(), resolve, Function.identity());
            query.add(encodeQueryComponent(key) + "=" + encodeQueryComponent(value));
        }
        String url = prefix + path + (query.isEmpty() ? "" : "?" + String.join("&", query)) + fragment;
        url = UrlResolver.resolve(applicationBaseUrl, url);

        // 2. En-tetes et corps : substitution brute.
        Map<String, String> headers = new LinkedHashMap<>();
        if (step.headers() != null && !step.headers().isBlank()) {
            try {
                Map<String, String> raw = objectMapper.readValue(step.headers(), new TypeReference<Map<String, String>>() { });
                if (raw != null) {
                    raw.forEach((name, value) -> headers.put(substitute(name, resolve, Function.identity()),
                            substitute(value == null ? "" : value, resolve, Function.identity())));
                }
            } catch (Exception e) {
                warnings.add("En-têtes de l'étape illisibles (JSON invalide) : ignorés.");
            }
        }
        String body = step.body() == null ? null : substitute(step.body(), resolve, Function.identity());
        return new BuiltRequest(url, headers, body, missing, warnings);
    }

    /**
     * Message clair pour des variables introuvables, avec une piste selon l'etape qui les capture
     * (numerotation 1..n dans l'ordre du scenario).
     */
    public static String missingMessage(Set<String> missing, int stepNumber, Map<String, Integer> captureStepByVariable) {
        List<String> parts = new ArrayList<>();
        for (String name : missing) {
            Integer captureStep = captureStepByVariable.get(name);
            String hint;
            if (captureStep == null) {
                hint = "ni capturée par une étape, ni colonne du CSV, ni variable du scénario";
            } else if (captureStep < stepNumber) {
                hint = "vérifiez la capture de l'étape " + captureStep + " ou le CSV";
            } else {
                hint = "elle n'est capturée qu'à l'étape " + captureStep + ", après cette étape";
            }
            parts.add("Variable ${" + name + "} introuvable : " + hint);
        }
        return String.join(" ; ", parts) + ".";
    }

    // ------------------------------------------------------------------ substitution

    /** ${variable} remplacees en une seule passe ; valeur introuvable laissee telle quelle (et signalee). */
    static String substitute(String template, Function<String, String> resolve, Function<String, String> encode) {
        if (template == null || template.isEmpty() || template.indexOf("${") < 0) return template;
        Matcher m = VARIABLE.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = resolve.apply(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? m.group() : encode.apply(value)));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String replaceUrlTokens(String template, Function<String, String> pathVariable,
            Function<String, String> resolve, Function<String, String> encode) {
        Matcher m = URL_TOKEN.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String replacement;
            if (m.group(1) != null) {
                String value = resolve.apply(m.group(1));
                replacement = value == null ? m.group() : encode.apply(value);
            } else {
                String name = m.group(2) != null ? m.group(2) : m.group(3);
                String value = pathVariable.apply(name);
                replacement = value.contains("${") ? value : encode.apply(value);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Encodage d'un segment de chemin : tout sauf les caracteres non reserves (RFC 3986). */
    static String encodePathSegment(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || "-._~".indexOf(c) >= 0) {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return out.toString();
    }

    static String encodeQueryComponent(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static Map<String, String> parsePathParams(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, String> parsed = objectMapper.readValue(json, new TypeReference<Map<String, String>>() { });
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            return Map.of();
        }
    }

    static List<QueryParam> parseQueryParams(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<QueryParam> parsed = objectMapper.readValue(json, new TypeReference<List<QueryParam>>() { });
            return parsed == null ? List.of() : parsed;
        } catch (Exception e) {
            return List.of();
        }
    }
}
