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

/**
 * Cookies d'UN utilisateur virtuel, geres automatiquement comme un navigateur : chaque Set-Cookie
 * recu est garde, puis renvoye dans l'en-tete Cookie des requetes suivantes vers le meme site
 * (domaine, chemin, expiration et attribut Secure respectes par java.net.CookieManager). Jamais
 * partage entre utilisateurs virtuels : un pot par VU, attache a ses deux clients HTTP.
 *
 *   - Politique ACCEPT_ORIGINAL_SERVER (celle d'un navigateur) : un cookie n'est accepte que
 *     pour le site qui l'a envoye (ou son domaine parent declare), jamais pour un site tiers.
 *   - Duree de vie : une ITERATION (voir {@link #clear()}, appele au debut de chaque iteration
 *     par le moteur), comme les captures de variables (VariableContext#startIteration) : chaque
 *     iteration rejoue le parcours avec une session vierge.
 *   - En-tete Cookie ecrit dans une etape : il est conserve, et les cookies automatiques sont
 *     AJOUTES a cote. Pour un meme nom de cookie, la valeur ecrite dans l'etape l'emporte (le
 *     cookie automatique de meme nom n'est pas envoye, sinon le serveur recevrait deux valeurs).
 *
 * Utilise par un seul thread (celui du VU) ; les methodes de CookieManager sont de toute facon
 * synchronisees.
 */
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

    /** Debut d'iteration : session vierge (tous les cookies oublies). */
    void clear() {
        manager.getCookieStore().removeAll();
    }

    /** Nombre de cookies gardes (tests). */
    int size() {
        return manager.getCookieStore().getCookies().size();
    }

    /** Noms des cookies ecrits dans l'en-tete Cookie de l'etape ("a=1; b=2" -> a, b). */
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
