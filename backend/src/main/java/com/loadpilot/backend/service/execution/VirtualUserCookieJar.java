package com.loadpilot.backend.service.execution;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Cookies d'un utilisateur virtuel, geres comme un navigateur (domaine, chemin, expiration, Secure). */
final class VirtualUserCookieJar extends CookieHandler {

    private final CookieManager manager = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);

    @Override
    public Map<String, List<String>> get(URI uri, Map<String, List<String>> requestHeaders) throws IOException {
        Map<String, List<String>> automatic = manager.get(uri, requestHeaders);
        Set<String> explicit = explicitCookieNames(requestHeaders);
        if (explicit.isEmpty()) return automatic;
        Map<String, List<String>> filtered = new LinkedHashMap<>();
        automatic.forEach((header, values) -> {
            List<String> kept = new ArrayList<>();
            for (String pair : values) {
                if (!explicit.contains(cookieName(pair))) kept.add(pair);
            }
            filtered.put(header, kept);
        });
        return filtered;
    }

    @Override
    public void put(URI uri, Map<String, List<String>> responseHeaders) throws IOException {
        manager.put(uri, responseHeaders);
    }

    /** Oublie tous les cookies (debut d'iteration). */
    void clear() {
        manager.getCookieStore().removeAll();
    }

    /** Nombre de cookies gardes (tests). */
    int size() {
        return manager.getCookieStore().getCookies().size();
    }

    /** Noms des cookies deja ecrits dans l'en-tete Cookie de l'etape ("a=1; b=2" -> a, b). */
    private static Set<String> explicitCookieNames(Map<String, List<String>> requestHeaders) {
        Set<String> names = new HashSet<>();
        if (requestHeaders == null) return names;
        requestHeaders.forEach((header, values) -> {
            if (!"cookie".equalsIgnoreCase(header) || values == null) return;
            for (String value : values) {
                for (String pair : value.split(";")) {
                    String name = cookieName(pair);
                    if (!name.isEmpty()) names.add(name);
                }
            }
        });
        return names;
    }

    private static String cookieName(String pair) {
        int equals = pair.indexOf('=');
        return (equals < 0 ? pair : pair.substring(0, equals)).trim();
    }
}
