package com.loadpilot.backend.service.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.service.auth.ApplicationAuthConfig;
import com.loadpilot.backend.service.auth.ApplicationAuthSpec;
import com.loadpilot.backend.service.execution.ApplicationAuthenticationException.Kind;
import com.loadpilot.backend.service.http.NetworkErrors;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resout l'authentification d'une Application en en-tetes/parametres prets
 * a l'emploi. Pour LOGIN_TOKEN, effectue une VRAIE requete de connexion
 * (POST JSON sur loginPath, resolu comme une etape contre l'URL de base),
 * puis extrait le jeton de la reponse avec le meme sous-ensemble JSONPath
 * que la capture de variable des etapes (voir JsonPathExtractor).
 *
 * Utilise par le moteur (une fois par Execution) et par le test de
 * disponibilite d'une Application. Ne journalise jamais de secret.
 */
public class ApplicationAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(ApplicationAuthenticator.class);

    private final ObjectMapper objectMapper;

    public ApplicationAuthenticator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ResolvedAuth authenticate(ApplicationAuthSpec spec, String applicationBaseUrl, long timeoutSeconds) {
        if (spec == null || spec.isNone()) {
            return ResolvedAuth.none();
        }
        ApplicationAuthConfig c = spec.config();
        return switch (spec.type()) {
            case BEARER_TOKEN -> new ResolvedAuth(Map.of("Authorization", "Bearer " + c.token()), Map.of(), c.token());
            case API_KEY -> c.apiKeyLocation() == com.loadpilot.backend.enums.ApiKeyLocation.QUERY
                    ? new ResolvedAuth(Map.of(), Map.of(c.apiKeyName(), c.apiKeyValue()), null)
                    : new ResolvedAuth(Map.of(c.apiKeyName(), c.apiKeyValue()), Map.of(), null);
            case BASIC -> {
                String raw = c.username() + ":" + (c.password() == null ? "" : c.password());
                String encoded = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
                yield new ResolvedAuth(Map.of("Authorization", "Basic " + encoded), Map.of(), null);
            }
            case LOGIN_TOKEN -> login(c, applicationBaseUrl, timeoutSeconds);
            case NONE -> ResolvedAuth.none();
        };
    }

    private ResolvedAuth login(ApplicationAuthConfig c, String applicationBaseUrl, long timeoutSeconds) {
        String loginUrl = UrlResolver.resolve(applicationBaseUrl, c.loginPath());
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        String body = c.loginBody() == null ? "" : c.loginBody();
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(loginUrl))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            // Jamais le message brut ni le corps envoye (identifiants).
            log.debug("Connexion a l'application echouee : {}", e.getClass().getSimpleName());
            throw new ApplicationAuthenticationException(Kind.UNREACHABLE,
                    "login impossible, " + NetworkErrors.describe(e, timeoutSeconds) + " sur " + c.loginPath());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApplicationAuthenticationException(Kind.UNREACHABLE, "login interrompu");
        } catch (IllegalArgumentException e) {
            throw new ApplicationAuthenticationException(Kind.UNREACHABLE, "URL de login invalide (" + c.loginPath() + ")");
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new ApplicationAuthenticationException(Kind.REJECTED, "login refusé (" + status + ")", status);
        }
        // Distingue "reponse non JSON" de "jeton absent" - jamais le corps
        // lui-meme dans le message (il contient le jeton ou des donnees).
        if (!isJson(response.body())) {
            String contentType = response.headers().firstValue("Content-Type").orElse("inconnu");
            throw new ApplicationAuthenticationException(Kind.INVALID_RESPONSE,
                    "réponse de login non JSON (Content-Type " + contentType + ")");
        }
        String token = JsonPathExtractor.extract(objectMapper, response.body(), c.tokenJsonPath());
        if (token == null || token.isBlank()) {
            throw new ApplicationAuthenticationException(Kind.TOKEN_NOT_FOUND,
                    "token introuvable à " + c.tokenJsonPath(), status);
        }
        String headerName = c.tokenHeaderName() == null || c.tokenHeaderName().isBlank()
                ? ApplicationAuthConfig.DEFAULT_TOKEN_HEADER : c.tokenHeaderName();
        // null = prefixe par defaut ; chaine vide = jeton envoye brut (choix explicite).
        String prefix = c.tokenPrefix() == null ? ApplicationAuthConfig.DEFAULT_TOKEN_PREFIX : c.tokenPrefix();
        return new ResolvedAuth(Map.of(headerName, prefix + token), Map.of(), token);
    }

    private boolean isJson(String body) {
        if (body == null || body.isBlank()) return false;
        try {
            objectMapper.readTree(body);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
