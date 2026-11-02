package com.loadpilot.backend.service.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.service.auth.ApplicationAuthConfig;
import com.loadpilot.backend.service.auth.ApplicationAuthSpec;
import com.loadpilot.backend.service.execution.ApplicationAuthenticationException.Kind;
import com.loadpilot.backend.service.http.NetworkErrors;
import com.loadpilot.backend.service.http.HttpClients;
import com.loadpilot.backend.service.http.NetworkProfile;
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

/** Resout l'authentification d'une application en en-tetes et parametres ; pour LOGIN_TOKEN, se connecte et extrait le jeton. */
public class ApplicationAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(ApplicationAuthenticator.class);

    private final ObjectMapper objectMapper;
    /** Identifiant du proxy d'entreprise pour la requete de connexion. */
    private final com.loadpilot.backend.service.http.ProxyAuthorization proxyAuthorization;

    public ApplicationAuthenticator(ObjectMapper objectMapper) {
        this(objectMapper, com.loadpilot.backend.service.http.ProxyAuthorization.current());
    }

    public ApplicationAuthenticator(ObjectMapper objectMapper, com.loadpilot.backend.service.http.ProxyAuthorization proxyAuthorization) {
        this.objectMapper = objectMapper;
        this.proxyAuthorization = proxyAuthorization;
    }

    public ResolvedAuth authenticate(ApplicationAuthSpec spec, String applicationBaseUrl, long timeoutSeconds) {
        return authenticate(spec, applicationBaseUrl, timeoutSeconds, NetworkProfile.inherit());
    }

    /** "network" : reglages reseau pour joindre l'application. */
    public ResolvedAuth authenticate(ApplicationAuthSpec spec, String applicationBaseUrl, long timeoutSeconds, NetworkProfile network) {
        if (network == null) network = NetworkProfile.inherit();
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
            case LOGIN_TOKEN -> login(c, applicationBaseUrl, timeoutSeconds, network);
            case NONE -> ResolvedAuth.none();
        };
    }

    private ResolvedAuth login(ApplicationAuthConfig c, String applicationBaseUrl, long timeoutSeconds, NetworkProfile network) {
        String loginUrl = UrlResolver.resolve(applicationBaseUrl, c.loginPath());
        HttpClient client = network.configure(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL))
                .build();
        String body = c.loginBody() == null ? "" : c.loginBody();
        HttpResponse<String> response;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(loginUrl))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            HttpRequest request = network.applyTo(builder, URI.create(loginUrl), proxyAuthorization).build();
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.debug("Connexion a l'application echouee : {}", e.getClass().getSimpleName());
            throw new ApplicationAuthenticationException(Kind.UNREACHABLE,
                    "login impossible, " + NetworkErrors.describe(e, timeoutSeconds) + " sur " + c.loginPath());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApplicationAuthenticationException(Kind.UNREACHABLE, "login interrompu");
        } catch (IllegalArgumentException e) {
            throw new ApplicationAuthenticationException(Kind.UNREACHABLE, "URL de login invalide (" + c.loginPath() + ")");
        } finally {
            HttpClients.shutdownQuietly(client);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new ApplicationAuthenticationException(Kind.REJECTED, "login refusé (" + status + ")", status);
        }
        // Jeton dans un en-tete de reponse : le corps n'est pas lu.
        if (c.tokenFromHeader()) {
            String token = ResponseHeaderExtractor.extract(response.headers(), c.tokenJsonPath());
            if (token == null) {
                throw new ApplicationAuthenticationException(Kind.TOKEN_NOT_FOUND,
                        "token introuvable : " + ResponseHeaderExtractor.explain(response.headers(), c.tokenJsonPath()), status);
            }
            return resolvedWith(c, token);
        }
        if (!isJson(response.body())) {
            String contentType = response.headers().firstValue("Content-Type").orElse("inconnu");
            throw new ApplicationAuthenticationException(Kind.INVALID_RESPONSE,
                    "réponse de login non JSON (Content-Type " + contentType + ")");
        }
        String token = JsonPathExtractor.extract(objectMapper, response.body(), c.tokenJsonPath());
        if (token == null || token.isBlank()) {
            String reason = JsonPathExtractor.explain(objectMapper, response.body(), c.tokenJsonPath());
            throw new ApplicationAuthenticationException(Kind.TOKEN_NOT_FOUND,
                    "token introuvable à " + c.tokenJsonPath() + (reason != null ? " : " + reason : ""), status);
        }
        return resolvedWith(c, token);
    }

    /** En-tete d'authentification avec le jeton obtenu et son prefixe. */
    private static ResolvedAuth resolvedWith(ApplicationAuthConfig c, String token) {
        String headerName = c.tokenHeaderName() == null || c.tokenHeaderName().isBlank()
                ? ApplicationAuthConfig.DEFAULT_TOKEN_HEADER : c.tokenHeaderName();
        // null = prefixe par defaut ; chaine vide = jeton envoye brut.
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
