package com.loadpilot.backend.service.http;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Confiance TLS propre a une application : autorite(s) de certification collees au format PEM.
 *
 * Le contexte construit fait confiance aux autorites de la JVM (magasin global : cacerts, ou
 * javax.net.ssl.trustStore s'il est defini) ET a celles de l'application - jamais a la place.
 * Verification standard (PKIX, dates, nom d'hote par HttpClient), aucun « tout accepter ».
 *
 * Isolation : chaque application a son propre SSLContext, utilise seulement par les clients HTTP de
 * cette application ; le contexte par defaut de la JVM (SSLContext.getDefault) n'est jamais modifie.
 * Pas de certificat client propre a l'application dans cette version : le certificat client global du
 * serveur (javax.net.ssl.keyStore), s'il existe, reste presente comme avant.
 */
public final class TlsTrust {

    /** Taille maximale du PEM colle (une chaine de quelques certificats tient en quelques Ko). */
    public static final int MAX_PEM_LENGTH = 65_536;
    public static final int MAX_CERTIFICATES = 20;
    private static final String BEGIN = "-----BEGIN CERTIFICATE-----";

    /** Resume d'un certificat pour l'interface (aucune donnee sensible). */
    public record CertificateInfo(String subject, String issuer, Instant notAfter, boolean expired) {
    }

    private TlsTrust() {
    }

    /** null si le PEM est vide ou valide, sinon le probleme en francais (message complet). */
    public static String problem(String pem) {
        if (pem == null || pem.isBlank()) return null;
        try {
            parse(pem);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /** Certificats du PEM ; IllegalArgumentException (message en francais) si illisible. */
    public static List<X509Certificate> parse(String pem) {
        if (pem == null || pem.isBlank()) return List.of();
        if (pem.length() > MAX_PEM_LENGTH) {
            throw new IllegalArgumentException("Autorité de certification : le texte ne doit pas dépasser 64 Ko.");
        }
        if (pem.contains("PRIVATE KEY")) {
            // Garde-fou : une cle privee n'a rien a faire ici (et ne doit jamais etre enregistree).
            throw new IllegalArgumentException("Autorité de certification : ce texte contient une clé privée. "
                    + "Collez uniquement le certificat public de l'autorité (-----BEGIN CERTIFICATE-----).");
        }
        if (!pem.contains(BEGIN)) {
            throw new IllegalArgumentException("Autorité de certification : format PEM attendu, commençant par " + BEGIN + ".");
        }
        Collection<? extends Certificate> certificates;
        try {
            certificates = CertificateFactory.getInstance("X.509")
                    .generateCertificates(new ByteArrayInputStream(pem.trim().getBytes(StandardCharsets.US_ASCII)));
        } catch (CertificateException e) {
            throw new IllegalArgumentException("Autorité de certification : certificat illisible (vérifiez le copier-coller, "
                    + "lignes BEGIN / END comprises).");
        }
        List<X509Certificate> result = new ArrayList<>();
        for (Certificate certificate : certificates) {
            if (certificate instanceof X509Certificate x509) result.add(x509);
        }
        if (result.isEmpty()) throw new IllegalArgumentException("Autorité de certification : aucun certificat trouvé.");
        if (result.size() > MAX_CERTIFICATES) {
            throw new IllegalArgumentException("Autorité de certification : " + MAX_CERTIFICATES + " certificats au maximum.");
        }
        return List.copyOf(result);
    }

    /** Resume des certificats (sujet, emetteur, expiration) ; liste vide si PEM vide ou illisible. */
    public static List<CertificateInfo> describe(String pem) {
        List<X509Certificate> certificates;
        try {
            certificates = parse(pem);
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        Instant now = Instant.now();
        return certificates.stream().map(c -> new CertificateInfo(c.getSubjectX500Principal().getName(),
                c.getIssuerX500Principal().getName(), c.getNotAfter().toInstant(), c.getNotAfter().toInstant().isBefore(now)))
                .toList();
    }

    /** Contexte TLS : autorites de la JVM + celles donnees. */
    public static SSLContext sslContext(List<X509Certificate> extraAuthorities) {
        try {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            int i = 0;
            for (X509Certificate jvm : defaultTrustManager().getAcceptedIssuers()) {
                store.setCertificateEntry("jvm-" + i++, jvm);
            }
            int j = 0;
            for (X509Certificate extra : extraAuthorities) {
                store.setCertificateEntry("application-" + j++, extra);
            }
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            // Certificat client global du serveur (LOADPILOT_TLS_KEYSTORE_*) conserve : sans cela, un
            // KeyManager null n'en presenterait aucun pour cette application.
            context.init(defaultKeyManagers(), factory.getTrustManagers(), null);
            return context;
        } catch (Exception e) {
            throw new IllegalStateException("Impossible de préparer la confiance TLS de l'application : "
                    + e.getClass().getSimpleName(), e);
        }
    }

    /**
     * Certificat client de la JVM, lu comme le fait le contexte par defaut (javax.net.ssl.keyStore,
     * keyStoreType, keyStorePassword - positionnes par start.ps1 / start.sh) ; null s'il n'y en a pas.
     * Le mot de passe n'apparait jamais dans un message.
     */
    static KeyManager[] defaultKeyManagers() throws Exception {
        String path = System.getProperty("javax.net.ssl.keyStore");
        if (path == null || path.isBlank() || "NONE".equals(path)) return null;
        String type = System.getProperty("javax.net.ssl.keyStoreType", KeyStore.getDefaultType());
        String password = System.getProperty("javax.net.ssl.keyStorePassword");
        char[] secret = password == null ? null : password.toCharArray();
        KeyStore store = KeyStore.getInstance(type);
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            store.load(in, secret);
        }
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(store, secret);
        return factory.getKeyManagers();
    }

    /** Gestionnaire de confiance par defaut de la JVM (celui qu'utilise un client sans reglage). */
    private static X509TrustManager defaultTrustManager() throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) return x509;
        }
        throw new IllegalStateException("aucun gestionnaire X.509 par défaut");
    }
}
