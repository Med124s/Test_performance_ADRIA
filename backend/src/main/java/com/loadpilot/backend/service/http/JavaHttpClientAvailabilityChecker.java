package com.loadpilot.backend.service.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
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
        long startedAt = System.nanoTime();
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(timeout)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .GET()
                    .build();

            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return new HttpAvailabilityResult(true, response.statusCode(), elapsedMs(startedAt), null);
        } catch (HttpTimeoutException e) {
            return new HttpAvailabilityResult(false, null, elapsedMs(startedAt),
                    "Delai d'attente depasse (" + timeout.toSeconds() + "s)");
        } catch (IOException e) {
            // Certaines IOException (ex: ConnectException sur Windows) n'ont
            // aucun message - ne jamais renvoyer un errorMessage vide/nul.
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.debug("Test de disponibilite echoue pour {} : {}", url, message);
            return new HttpAvailabilityResult(false, null, elapsedMs(startedAt), message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new HttpAvailabilityResult(false, null, elapsedMs(startedAt), "Requete interrompue.");
        } catch (IllegalArgumentException e) {
            // URI.create()/HttpRequest.newBuilder() rejette une URL malformee.
            return new HttpAvailabilityResult(false, null, elapsedMs(startedAt), "URL invalide : " + e.getMessage());
        }
    }

    private long elapsedMs(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }
}
