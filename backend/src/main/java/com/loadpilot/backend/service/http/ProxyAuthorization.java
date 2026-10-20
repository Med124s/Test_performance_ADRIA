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

/**
 * Authentification Basic aupres du proxy sortant d'entreprise (variables d'environnement
 * LOADPILOT_HTTPS_PROXY_USERNAME / LOADPILOT_HTTPS_PROXY_PASSWORD, voir backend/start.ps1 et le README,
 * « Préparer LoadPilot pour une application cible inconnue »).
 *
 * POURQUOI UN EN-TETE PLUTOT QU'UN Authenticator (verifie avec Java 21) :
 *   - -Dhttps.proxyUser/-Dhttps.proxyPassword et Authenticator.setDefault(...) sont ignores par
 *     java.net.http.HttpClient (le proxy recoit un CONNECT sans identifiant) ;
 *   - un Authenticator pose sur le client fonctionne, mais il change le traitement des 401 de
 *     L'APPLICATION TESTEE : un 401 sans en-tete WWW-Authenticate, ou avec un defi Basic, devient une
 *     IOException au lieu d'une reponse 401 (renouvellement du jeton de la Connexion automatique casse).
 * L'en-tete Proxy-Authorization pose sur la requete est envoye par Java dans le CONNECT du tunnel HTTPS,
 * et JAMAIS a l'application elle-meme (retire de la requete tunnelisee comme d'une requete directe) ;
 * les 401 de l'application restent des 401. Il exige -Djdk.http.auth.tunneling.disabledSchemes= (Basic
 * est interdit par defaut pour les tunnels), ajoute par les scripts de demarrage.
 *
 * Basic uniquement (pas de NTLM / Kerberos, non geres par java.net.http). Sans identifiant configure,
 * rien n'est ajoute : comportement strictement inchange.
 */
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

    /** Identifiants lus dans l'environnement du backend (une seule fois, au chargement). */
    public static ProxyAuthorization current() {
        return CURRENT;
    }

    /** Aucun identifiant : n'ajoute jamais rien. */
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

    /**
     * Ajoute Proxy-Authorization a la requete seulement si un identifiant est configure ET si Java
     * envoie cette adresse a un proxy (jamais pour une adresse directe : localhost, http:// sans proxy...).
     */
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
