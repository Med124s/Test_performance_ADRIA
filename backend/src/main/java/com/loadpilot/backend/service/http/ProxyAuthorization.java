package com.loadpilot.backend.service.http;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/** Authentification Basic aupres du proxy d'entreprise (LOADPILOT_HTTPS_PROXY_USERNAME / LOADPILOT_HTTPS_PROXY_PASSWORD). */
public final class ProxyAuthorization {

    public static final String HEADER = "Proxy-Authorization";

    private static final ProxyAuthorization CURRENT =
            fromEnvironment(System::getenv, ProxySelector::getDefault);

    /** Valeur de l'en-tete (« Basic … »), ou null si aucun identifiant n'est configure. */
    private final String headerValue;
    /** Selecteur de proxy de Java, lu a chaque requete (memes regles que HttpClient : https.proxyHost, nonProxyHosts). */
    private final Supplier<ProxySelector> selector;

    private ProxyAuthorization(String headerValue, Supplier<ProxySelector> selector) {
        this.headerValue = headerValue;
        this.selector = selector;
    }

    /** Identifiants lus dans l'environnement du backend. */
    public static ProxyAuthorization current() {
        return CURRENT;
    }

    /** Sans identifiant. */
    public static ProxyAuthorization none() {
        return new ProxyAuthorization(null, ProxySelector::getDefault);
    }

    static ProxyAuthorization fromEnvironment(Function<String, String> env, Supplier<ProxySelector> selector) {
        return of(env.apply("LOADPILOT_HTTPS_PROXY_USERNAME"), env.apply("LOADPILOT_HTTPS_PROXY_PASSWORD"), selector);
    }

    /** Identifiant et mot de passe (null ou vide = aucun) ; selecteur de proxy fourni (tests). */
    public static ProxyAuthorization of(String username, String password, Supplier<ProxySelector> selector) {
        if (username == null || username.isBlank()) return new ProxyAuthorization(null, selector);
        String token = username.trim() + ":" + (password == null ? "" : password);
        return new ProxyAuthorization("Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)), selector);
    }

    public boolean configured() {
        return headerValue != null;
    }

    /** Ajoute Proxy-Authorization si un identifiant est configure et si la requete passe par un proxy. */
    public HttpRequest.Builder applyTo(HttpRequest.Builder builder, URI uri) {
        if (headerValue != null && viaProxy(uri)) builder.setHeader(HEADER, headerValue);
        return builder;
    }

    boolean viaProxy(URI uri) {
        try {
            ProxySelector proxySelector = selector.get();
            if (proxySelector == null || uri == null) return false;
            List<Proxy> proxies = proxySelector.select(uri);
            return proxies != null && proxies.stream().anyMatch(p -> p.type() != Proxy.Type.DIRECT);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Selecteur fixe (tests) : toutes les adresses passent par ce proxy. */
    static ProxySelector fixed(Proxy proxy) {
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return List.of(proxy);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            }
        };
    }
}
