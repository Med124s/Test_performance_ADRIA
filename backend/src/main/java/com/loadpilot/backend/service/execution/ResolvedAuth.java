package com.loadpilot.backend.service.execution;

import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Authentification deja resolue (en-tetes et parametres de requete a
 * ajouter), prete a etre appliquee a chaque requete vers l'Application.
 * Pour LOGIN_TOKEN, "token" est le jeton obtenu a la connexion (expose aux
 * etapes via la variable ${authToken}).
 */
public record ResolvedAuth(Map<String, String> headers, Map<String, String> queryParams, String token) {

    public static final String TOKEN_VARIABLE = "authToken";

    public static ResolvedAuth none() {
        return new ResolvedAuth(Map.of(), Map.of(), null);
    }

    /** Ajoute les parametres de requete d'authentification, sauf ceux deja
     * presents dans l'URL (une etape peut toujours surcharger). */
    public String applyToUrl(String url) {
        if (queryParams.isEmpty()) return url;
        String query = queryParams.entrySet().stream()
                .filter(e -> !url.contains("?" + e.getKey() + "=") && !url.contains("&" + e.getKey() + "="))
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
        if (query.isEmpty()) return url;
        return url + (url.contains("?") ? "&" : "?") + query;
    }

    /** Ajoute les en-tetes d'authentification, sauf ceux que l'etape definit
     * deja elle-meme (comparaison insensible a la casse) : l'etape prime. */
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
