package com.loadpilot.backend.service.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Implementation reelle unique (Java 21 java.net.http.HttpClient, aucune
 * dependance externe) - envoie une vraie requete GET, comme le fait deja le
 * frontend existant (Applications.tsx handleTestAndAdd), pour rester
 * coherent avec le comportement deja connu du produit.
 */
@Component
public class JavaHttpClientAvailabilityChecker implements HttpAvailabilityChecker {

    private static final Logger log = LoggerFactory.getLogger(JavaHttpClientAvailabilityChecker.class);

    @Override
    public HttpAvailabilityResult check(String url, Duration timeout) {
        return check(url, Map.of(), timeout);
    }

    @Override
    public HttpAvailabilityResult check(String url, Map<String, String> headers, Duration timeout) {
        return check(url, headers, timeout, NetworkProfile.inherit());
    }

    @Override
    public HttpAvailabilityResult check(String url, Duration timeout, NetworkProfile network) {
        return check(url, Map.of(), timeout, network);
    }

    @Override
    public HttpAvailabilityResult check(String url, Map<String, String> headers, Duration timeout, NetworkProfile network) {
        long startedAt = System.nanoTime();
        HttpClient client = null;
        try {
            // Proxy de l'application (ou du serveur, ou aucun) : voir NetworkProfile.
            client = network.configure(HttpClient.newBuilder()
                    .connectTimeout(timeout)
                    .followRedirects(HttpClient.Redirect.NORMAL))
                    .build();
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .GET();
            headers.forEach(builder::header);
            // Proxy avec identifiant : en-tete ajoute seulement si la requete passe par lui.
            HttpRequest request = network.applyTo(builder, URI.create(url), ProxyAuthorization.current()).build();

            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return new HttpAvailabilityResult(true, response.statusCode(), elapsedMs(startedAt), null);
        } catch (IOException e) {
            // Message lisible, jamais le message brut (peut contenir l'URL,
            // donc une cle API en parametre) ; log sans la query string.
            String message = NetworkErrors.describe(e, timeout.toSeconds());
            log.debug("Test de disponibilite echoue pour {} : {}", NetworkErrors.redact(url), e.getClass().getSimpleName());
            return new HttpAvailabilityResult(false, null, elapsedMs(startedAt), message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new HttpAvailabilityResult(false, null, elapsedMs(startedAt), "requête interrompue");
        } catch (IllegalArgumentException e) {
            // URI.create()/HttpRequest.newBuilder() rejette une URL malformee.
            return new HttpAvailabilityResult(false, null, elapsedMs(startedAt), "URL invalide");
        } finally {
            // Corps jamais lu (discarding) : rien n'est plus attendu, client ferme tout de suite.
            HttpClients.shutdownQuietly(client);
        }
    }

    private long elapsedMs(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }
}
