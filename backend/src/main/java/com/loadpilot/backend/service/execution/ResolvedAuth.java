package com.loadpilot.backend.service.execution;

import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Authentification resolue (en-tetes et parametres a ajouter) ; pour LOGIN_TOKEN, "token" est le jeton obtenu a la connexion. */
public record ResolvedAuth(Map<String, String> headers, Map<String, String> queryParams, String token) {

    public static final String TOKEN_VARIABLE = "authToken";

    public static ResolvedAuth none() {
        return new ResolvedAuth(Map.of(), Map.of(), null);
    }

    /** Ajoute les parametres d'authentification absents de l'URL. */
    public String applyToUrl(String url) {
        if (queryParams.isEmpty()) return url;
        String query = queryParams.entrySet().stream()
                .filter(e -> !url.contains("?" + e.getKey() + "=") && !url.contains("&" + e.getKey() + "="))
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
        if (query.isEmpty()) return url;
        return url + (url.contains("?") ? "&" : "?") + query;
    }

    /** Ajoute les en-tetes d'authentification que l'etape ne definit pas elle-meme. */
    public void applyHeaders(HttpRequest.Builder builder, Set<String> stepHeaderNames) {
        Set<String> lower = stepHeaderNames.stream().map(String::toLowerCase).collect(Collectors.toSet());
        headers.forEach((name, value) -> {
            if (!lower.contains(name.toLowerCase())) builder.header(name, value);
        });
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
