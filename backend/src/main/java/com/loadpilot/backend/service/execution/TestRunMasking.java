package com.loadpilot.backend.service.execution;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masquage des secrets dans le resultat d'un test detaille (voir StepTestRunner) : en-tetes
 * sensibles, champs JSON password/token/secret/apiKey, parametres d'URL sensibles et toute
 * occurrence litterale d'une valeur secrete connue (jeton de login, cle API, mot de passe...).
 */
public final class TestRunMasking {

    public static final String MASK = "••••••";

    private static final Set<String> SENSITIVE_HEADERS =
            Set.of("authorization", "proxy-authorization", "x-api-key", "cookie", "set-cookie");

    private static final String SENSITIVE_WORDS = "password|passwd|pwd|token|secret|api[_-]?key";
    private static final Pattern SENSITIVE_NAME = Pattern.compile(".*(" + SENSITIVE_WORDS + ").*", Pattern.CASE_INSENSITIVE);
    private static final Pattern SENSITIVE_JSON_FIELD = Pattern.compile(
            "(\"[^\"]*(?:" + SENSITIVE_WORDS + ")[^\"]*\"\\s*:\\s*)(\"(?:[^\"\\\\]|\\\\.)*\"|-?\\d[\\d.eE+-]*)",
            Pattern.CASE_INSENSITIVE);

    /** Les valeurs trop courtes ne sont pas remplacees litteralement (faux positifs). */
    private static final int MIN_SECRET_LENGTH = 4;

    private TestRunMasking() {
    }

    public static boolean isSensitiveName(String name) {
        return name != null && SENSITIVE_NAME.matcher(name).matches();
    }

    private static final Pattern SENSITIVE_HTML_NAME = Pattern.compile(".*(" + SENSITIVE_WORDS + "|csrf|xsrf|session).*", Pattern.CASE_INSENSITIVE);

    /**
     * Capture depuis une page HTML : selecteur, ou nom (name/id) de l'element retenu, qui ressemble a
     * un jeton, un mot de passe ou une session (ex. input[name=csrf_token], authenticity_token).
     */
    public static boolean isSensitiveHtmlCapture(String spec, String elementName) {
        return (spec != null && SENSITIVE_HTML_NAME.matcher(spec).matches())
                || (elementName != null && SENSITIVE_HTML_NAME.matcher(elementName).matches());
    }

    /** Capture depuis un en-tete (nom, ou "Set-Cookie:NOM") : en-tete d'authentification ou de cookie, ou nom sensible. */
    public static boolean isSensitiveHeaderCapture(String spec) {
        if (spec == null) return false;
        String name = spec.split(":", 2)[0].trim().toLowerCase(Locale.ROOT);
        return SENSITIVE_HEADERS.contains(name) || isSensitiveName(spec);
    }

    public static Map<String, String> maskHeaders(Map<String, String> headers, Set<String> extraSensitiveNames,
            Collection<String> secrets) {
        Map<String, String> masked = new LinkedHashMap<>();
        headers.forEach((name, value) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            boolean sensitive = SENSITIVE_HEADERS.contains(lower) || extraSensitiveNames.contains(lower);
            masked.put(name, sensitive ? MASK : maskSecrets(value, secrets));
        });
        return masked;
    }

    /** Corps (envoye ou recu) : valeurs secretes connues puis champs JSON sensibles. Fonctionne
     * aussi sur un JSON tronque (expression reguliere, jamais de re-serialisation). */
    public static String maskBody(String body, Collection<String> secrets) {
        if (body == null || body.isEmpty()) return body;
        String result = maskSecrets(body, secrets);
        Matcher m = SENSITIVE_JSON_FIELD.matcher(result);
        return m.replaceAll(r -> Matcher.quoteReplacement(r.group(1) + "\"" + MASK + "\""));
    }

    /** URL : parametres de requete sensibles (ex. api_key=...) et valeurs secretes connues. */
    public static String maskUrl(String url, Set<String> extraSensitiveParams, Collection<String> secrets) {
        if (url == null) return null;
        String result = maskSecrets(url, secrets);
        int q = result.indexOf('?');
        if (q < 0) return result;
        StringBuilder out = new StringBuilder(result.substring(0, q + 1));
        String[] pairs = result.substring(q + 1).split("&", -1);
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) out.append('&');
            String pair = pairs[i];
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            boolean sensitive = eq >= 0 && (isSensitiveName(name)
                    || extraSensitiveParams.contains(name.toLowerCase(Locale.ROOT)));
            out.append(sensitive ? name + "=" + MASK : pair);
        }
        return out.toString();
    }

    /** Valeurs des champs JSON sensibles d'un corps (ex. le mot de passe du corps de login),
     * pour les masquer ensuite partout ou elles reapparaitraient. */
    public static List<String> sensitiveJsonValues(String json) {
        List<String> values = new ArrayList<>();
        if (json == null || json.isBlank()) return values;
        Matcher m = SENSITIVE_JSON_FIELD.matcher(json);
        while (m.find()) {
            String raw = m.group(2);
            values.add(raw.startsWith("\"") ? raw.substring(1, raw.length() - 1) : raw);
        }
        return values;
    }

    public static String maskSecrets(String text, Collection<String> secrets) {
        if (text == null || text.isEmpty() || secrets.isEmpty()) return text;
        String result = text;
        // Les plus longues d'abord : "Bearer abc" avant "abc".
        for (String secret : secrets.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            if (secret != null && secret.length() >= MIN_SECRET_LENGTH) {
                result = result.replace(secret, MASK);
            }
        }
        return result;
    }
}
