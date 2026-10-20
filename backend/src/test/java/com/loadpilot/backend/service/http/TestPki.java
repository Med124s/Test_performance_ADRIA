package com.loadpilot.backend.service.http;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;

/**
 * PKI de test generee a l'execution avec le keytool du JDK (aucun certificat ni magasin dans le depot) :
 * une autorite, un certificat serveur pour « localhost » signe par elle, un certificat client optionnel,
 * et un serveur HTTPS local qui presente le certificat serveur.
 */
public final class TestPki {

    public static final String PASSWORD = "test-only";

    /** PEM de l'autorite (certificat public seulement). */
    public final String caPem;
    private final Path dir;
    private final String name;
    private final Path caKeystore;
    private final Path serverKeystore;

    private TestPki(Path dir, String name, String caPem, Path caKeystore, Path serverKeystore) {
        this.dir = dir;
        this.name = name;
        this.caPem = caPem;
        this.caKeystore = caKeystore;
        this.serverKeystore = serverKeystore;
    }

    /** Autorite + certificat serveur « localhost » (SAN dns:localhost uniquement) signe par elle. */
    public static TestPki create(Path dir, String caName) throws Exception {
        Path ca = dir.resolve(caName + "-ca.p12");
        Path caPem = dir.resolve(caName + "-ca.pem");
        keytool("-genkeypair", "-alias", "ca", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=" + caName,
                "-ext", "bc:c", "-validity", "2", "-keystore", ca.toString(), "-storetype", "PKCS12",
                "-storepass", PASSWORD, "-keypass", PASSWORD);
        keytool("-exportcert", "-alias", "ca", "-rfc", "-file", caPem.toString(), "-keystore", ca.toString(), "-storepass", PASSWORD);
        TestPki pki = new TestPki(dir, caName, Files.readString(caPem, StandardCharsets.US_ASCII), ca, dir.resolve(caName + "-server.p12"));
        pki.issue(pki.serverKeystore, "server", "CN=localhost", "serverAuth", "SAN=dns:localhost");
        return pki;
    }

    /** Magasin PKCS12 (mot de passe PASSWORD) d'un certificat client signe par cette autorite. */
    public Path createClientKeystore() throws Exception {
        Path client = dir.resolve(name + "-client.p12");
        issue(client, "client", "CN=loadpilot-client", "clientAuth", null);
        return client;
    }

    /** Serveur HTTPS local (port libre) qui repond 200 « ok » sur tout chemin. */
    public HttpsServer startServer() throws Exception {
        return startServer(false);
    }

    /** requireClientCertificate : le serveur exige un certificat client signe par cette autorite. */
    public HttpsServer startServer(boolean requireClientCertificate) throws Exception {
        KeyStore store = load(serverKeystore);
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, PASSWORD.toCharArray());
        KeyStore trusted = KeyStore.getInstance("PKCS12");
        trusted.load(null, null);
        trusted.setCertificateEntry("ca", CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(caPem.getBytes(StandardCharsets.US_ASCII))));
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trusted);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
        HttpsServer server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context) {
            @Override
            public void configure(HttpsParameters params) {
                SSLParameters parameters = context.getDefaultSSLParameters();
                parameters.setNeedClientAuth(requireClientCertificate);
                params.setSSLParameters(parameters);
            }
        });
        server.createContext("/", ex -> {
            byte[] body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        return server;
    }

    /** Cle + certificat signe par l'autorite, avec la chaine complete, dans un nouveau magasin. */
    private void issue(Path keystore, String alias, String dname, String eku, String san) throws Exception {
        Path csr = dir.resolve(name + "-" + alias + ".csr");
        Path pem = dir.resolve(name + "-" + alias + ".pem");
        Path caPemFile = dir.resolve(name + "-ca.pem");
        keytool("-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048", "-dname", dname,
                "-validity", "2", "-keystore", keystore.toString(), "-storetype", "PKCS12", "-storepass", PASSWORD, "-keypass", PASSWORD);
        keytool("-certreq", "-alias", alias, "-file", csr.toString(), "-keystore", keystore.toString(), "-storepass", PASSWORD);
        List<String> gencert = new ArrayList<>(List.of("-gencert", "-alias", "ca", "-infile", csr.toString(), "-outfile", pem.toString(),
                "-rfc", "-ext", "eku=" + eku, "-validity", "2", "-keystore", caKeystore.toString(), "-storepass", PASSWORD));
        if (san != null) gencert.addAll(List.of("-ext", san));
        keytool(gencert.toArray(String[]::new));
        keytool("-importcert", "-alias", "ca", "-file", caPemFile.toString(), "-noprompt", "-keystore", keystore.toString(), "-storepass", PASSWORD);
        keytool("-importcert", "-alias", alias, "-file", pem.toString(), "-noprompt", "-keystore", keystore.toString(), "-storepass", PASSWORD);
    }

    private static KeyStore load(Path path) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(path)) {
            store.load(in, PASSWORD.toCharArray());
        }
        return store;
    }

    private static void keytool(String... args) throws IOException, InterruptedException {
        String exe = System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool";
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", exe).toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IllegalStateException("keytool " + args[0] + " : " + output);
    }
}
