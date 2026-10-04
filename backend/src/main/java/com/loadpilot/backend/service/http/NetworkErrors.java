package com.loadpilot.backend.service.http;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import javax.net.ssl.SSLException;

/**
 * Traduit une erreur reseau en message lisible, SANS jamais reprendre le
 * message brut de l'exception (qui peut contenir l'URL, donc une cle API
 * passee en parametre de requete).
 */
public final class NetworkErrors {

    private NetworkErrors() {
    }

    public static String describe(IOException e, long timeoutSeconds) {
        if (e instanceof HttpConnectTimeoutException) {
            return "délai de connexion dépassé (" + timeoutSeconds + " s)";
        }
        if (e instanceof HttpTimeoutException) {
            return "délai de réponse dépassé (" + timeoutSeconds + " s)";
        }
        if (hasCause(e, UnknownHostException.class) || hasCause(e, UnresolvedAddressException.class)) {
            return "hôte introuvable (DNS)";
        }
        if (hasCause(e, SSLException.class)) {
            return "erreur TLS/SSL lors de la connexion";
        }
        if (hasCause(e, ConnectException.class)) {
            return "connexion refusée (serveur arrêté ou port fermé)";
        }
        return "erreur réseau (" + e.getClass().getSimpleName() + ")";
    }

    /** URL sans parametres de requete ni identifiants, pour les logs. */
    /** Categorie stable (TIMEOUT, DNS, TLS, CONNECTION_REFUSED, NETWORK) - meme ordre de
     * detection que describe(), pour que le frontend puisse proposer une piste adaptee. */
    public static String kind(IOException e) {
        if (e instanceof HttpTimeoutException) return "TIMEOUT";
        if (hasCause(e, UnknownHostException.class) || hasCause(e, UnresolvedAddressException.class)) return "DNS";
        if (hasCause(e, SSLException.class)) return "TLS";
        if (hasCause(e, ConnectException.class)) return "CONNECTION_REFUSED";
        return "NETWORK";
    }

    public static String redact(String url) {
        if (url == null) return null;
        String withoutQuery = url.split("[?#]", 2)[0];
        return withoutQuery.replaceFirst("//[^/@]*@", "//***@");
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) return true;
            if (t.getCause() == t) break;
        }
        return false;
    }
}
