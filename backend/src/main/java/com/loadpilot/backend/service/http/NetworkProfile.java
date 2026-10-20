package com.loadpilot.backend.service.http;

import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.enums.ProxyMode;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.net.ssl.SSLContext;

/**
 * Reglages reseau pour joindre UNE application testee, appliques a chaque client HTTP qui lui parle
 * (moteur de charge, test de validation, Connexion automatique, bouton « Tester », lecture CPU / RAM) :
 *   - le proxy : global (comme avant), aucun, ou propre a l'application ;
 *   - la confiance TLS : magasin global de la JVM (comme avant), ou complete par les autorites de
 *     certification de l'application (voir TlsTrust) - SSLContext propre a ce profil, jamais partage
 *     avec les autres applications. Pas encore de certificat client.
 *
 * Deux points d'entree seulement, pour que chaque appelant reste simple :
 *   - configure(HttpClient.Builder) a la construction du client ;
 *   - applyTo(HttpRequest.Builder, URI, global) sur chaque requete (identifiant du proxy).
 * Immuable, partageable entre threads (un profil par execution, commun a tous ses VU).
 */
public final class NetworkProfile {

    private static final NetworkProfile INHERIT = new NetworkProfile(ProxyMode.INHERIT, null, null, null, null, 0);
    private static final NetworkProfile DIRECT = new NetworkProfile(ProxyMode.NONE, null, null, ProxyAuthorization.none(), null, 0);

    private final ProxyMode mode;
    /** Proxy de l'application (CUSTOM), sinon null. */
    private final InetSocketAddress proxyAddress;
    private final String proxyUsername;
    /** Identifiant du proxy : propre a l'application (CUSTOM), aucun (NONE), null = celui du serveur (INHERIT). */
    private final ProxyAuthorization proxyAuthorization;
    /** Confiance TLS de l'application (JVM + ses autorites) ; null = contexte par defaut de la JVM. */
    private final SSLContext sslContext;
    private final int trustedAuthorities;

    private NetworkProfile(ProxyMode mode, InetSocketAddress proxyAddress, String proxyUsername, ProxyAuthorization proxyAuthorization,
            SSLContext sslContext, int trustedAuthorities) {
        this.mode = mode;
        this.proxyAddress = proxyAddress;
        this.proxyUsername = proxyUsername;
        this.proxyAuthorization = proxyAuthorization;
        this.sslContext = sslContext;
        this.trustedAuthorities = trustedAuthorities;
    }

    /**
     * Meme profil, avec en plus les autorites de certification du PEM (vide = profil inchange, confiance
     * de la JVM seule). IllegalArgumentException (message en francais) si le PEM est illisible.
     */
    public NetworkProfile withTrustedAuthorities(String pem) {
        List<X509Certificate> authorities = TlsTrust.parse(pem);
        if (authorities.isEmpty()) return this;
        return new NetworkProfile(mode, proxyAddress, proxyUsername, proxyAuthorization, TlsTrust.sslContext(authorities),
                authorities.size());
    }

    /** Meme confiance TLS, sans aucun proxy (« Monitoring sans proxy »). */
    public NetworkProfile withoutProxy() {
        if (sslContext == null) return DIRECT;
        return new NetworkProfile(ProxyMode.NONE, null, null, ProxyAuthorization.none(), sslContext, trustedAuthorities);
    }

    /** true si l'application a ses propres autorites de certification. */
    public boolean hasCustomTrust() {
        return sslContext != null;
    }

    /** Proxy global du serveur (variables LOADPILOT_HTTPS_PROXY_*) : comportement d'avant, strictement. */
    public static NetworkProfile inherit() {
        return INHERIT;
    }

    /** Jamais de proxy, meme si un proxy global est configure. */
    public static NetworkProfile direct() {
        return DIRECT;
    }

    /** Proxy propre a l'application ; IllegalArgumentException (message en francais) si invalide. */
    public static NetworkProfile custom(String host, Integer port, String username, String password) {
        String problem = proxyProblem(host, port, username);
        if (problem != null) throw new IllegalArgumentException(problem);
        String user = username == null || username.isBlank() ? null : username.trim();
        // Adresse non resolue : la resolution DNS se fait a la connexion (comme pour https.proxyHost).
        InetSocketAddress address = InetSocketAddress.createUnresolved(host.trim(), port);
        ProxySelector selector = ProxySelector.of(address);
        return new NetworkProfile(ProxyMode.CUSTOM, address, user, ProxyAuthorization.of(user, password, () -> selector), null, 0);
    }

    /** Reglages enregistres d'une application ; null ou INHERIT = proxy global ; sans autorite = confiance de la JVM. */
    public static NetworkProfile of(Application application) {
        if (application == null) return INHERIT;
        NetworkProfile proxy = application.getProxyMode() == null ? INHERIT : switch (application.getProxyMode()) {
            case INHERIT -> INHERIT;
            case NONE -> DIRECT;
            case CUSTOM -> custom(application.getProxyHost(), application.getProxyPort(), application.getProxyUsername(),
                    application.getProxyPassword());
        };
        return proxy.withTrustedAuthorities(application.getTlsCaCertificate());
    }

    /** Lecture CPU / RAM : profil de l'application, ou en direct si « Monitoring sans proxy » (confiance TLS gardee). */
    public static NetworkProfile forMonitoring(Application application) {
        NetworkProfile profile = of(application);
        return application != null && Boolean.TRUE.equals(application.getMonitoringWithoutProxy()) ? profile.withoutProxy() : profile;
    }

    /**
     * Meme regles que les scripts de demarrage (start.ps1 / start.sh) : hote obligatoire, sans schema
     * ni espace ; port de 1 a 65535 ; identifiant sans « : » (separateur de l'en-tete Basic).
     * null si valide.
     */
    public static String proxyProblem(String host, Integer port, String username) {
        if (host == null || host.isBlank()) return "Indiquez l'hôte du proxy (ex : proxy.banque.local).";
        String h = host.trim();
        if (h.contains("://") || h.contains("/") || h.chars().anyMatch(Character::isWhitespace)) {
            return "Hôte du proxy invalide : indiquez seulement le nom ou l'adresse (sans http:// ni chemin).";
        }
        if (port == null) return "Indiquez le port du proxy (ex : 8080).";
        if (port < 1 || port > 65535) return "Port du proxy invalide (" + port + ") : attendu un port entre 1 et 65535.";
        if (username != null && username.contains(":")) return "L'identifiant du proxy ne doit pas contenir « : ».";
        return null;
    }

    public ProxyMode mode() {
        return mode;
    }

    /**
     * Proxy de l'application joignable ? null s'il n'y a pas de proxy propre a l'application (proxy du
     * serveur, aucun) ou s'il accepte la connexion ; sinon le message complet, qui designe le proxy.
     */
    public String proxyUnreachable() {
        if (mode != ProxyMode.CUSTOM) return null;
        String problem = ProxyReachability.problem(proxyAddress.getHostString(), proxyAddress.getPort());
        return problem == null ? null : ProxyReachability.message(proxyAddress.getHostString(), proxyAddress.getPort(), problem);
    }

    /** Applique les reglages a la construction d'un client : proxy, et confiance TLS de l'application. */
    public HttpClient.Builder configure(HttpClient.Builder builder) {
        switch (mode) {
            case CUSTOM -> builder.proxy(ProxySelector.of(proxyAddress));
            case NONE -> builder.proxy(HttpClient.Builder.NO_PROXY);
            case INHERIT -> { } // selecteur par defaut de Java (https.proxyHost...), comme avant
        }
        // Sans autorite propre : aucun sslContext fixe, le client garde le contexte par defaut (comme avant).
        if (sslContext != null) builder.sslContext(sslContext);
        return builder;
    }

    /**
     * Identifiant du proxy sur une requete (en-tete Proxy-Authorization, seulement si elle passe par un
     * proxy). "global" : identifiant du serveur, utilise en INHERIT (celui de l'appelant, pour les tests).
     */
    public HttpRequest.Builder applyTo(HttpRequest.Builder builder, URI uri, ProxyAuthorization global) {
        ProxyAuthorization authorization = proxyAuthorization != null ? proxyAuthorization : global;
        return authorization == null ? builder : authorization.applyTo(builder, uri);
    }

    /** Description pour les journaux, jamais le mot de passe. */
    @Override
    public String toString() {
        String proxy = switch (mode) {
            case INHERIT -> "proxy du serveur";
            case NONE -> "sans proxy";
            case CUSTOM -> "proxy " + proxyAddress.getHostString() + ":" + proxyAddress.getPort()
                    + (proxyUsername != null ? " (identifiant " + proxyUsername + ")" : "");
        };
        return sslContext == null ? proxy : proxy + ", " + trustedAuthorities + " autorité(s) de certification propre(s)";
    }
}
