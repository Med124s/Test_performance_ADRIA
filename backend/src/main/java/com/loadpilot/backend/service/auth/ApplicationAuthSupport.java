package com.loadpilot.backend.service.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.dto.request.ApplicationAuthRequest;
import com.loadpilot.backend.dto.response.ApplicationAuthResponse;
import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.enums.ApiKeyLocation;
import com.loadpilot.backend.enums.ApplicationAuthType;
import com.loadpilot.backend.exception.LoadConfigurationException;
import com.loadpilot.backend.service.execution.ApplicationAuthenticator;
import com.loadpilot.backend.service.execution.ResolvedAuth;
import java.util.regex.Pattern;
import org.mapstruct.Named;
import org.springframework.stereotype.Component;

/**
 * Point unique de gestion de l'authentification d'une Application :
 * validation par type, conservation des secrets en mise a jour,
 * (de)serialisation JSON, vue SANS secret pour l'API, et resolution reelle
 * (connexion comprise) pour le test de disponibilite.
 */
@Component
public class ApplicationAuthSupport {

    /** Nom d'en-tete HTTP valide (token RFC 7230). */
    private static final Pattern HEADER_NAME = Pattern.compile("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$");
    /** Nom de parametre de requete : ni espace ni separateur d'URL. */
    private static final Pattern QUERY_NAME = Pattern.compile("^[^\\s&=?#]+$");
    /** Sous-ensemble supporte par JsonPathExtractor : segments separes par des points. */
    private static final Pattern JSON_PATH = Pattern.compile("^(\\$\\.?)?[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s");
    private static final Pattern LINE_BREAK = Pattern.compile("[\\r\\n\\x00]");

    private final ObjectMapper objectMapper;
    private final ApplicationAuthenticator authenticator;

    public ApplicationAuthSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.authenticator = new ApplicationAuthenticator(objectMapper);
    }

    /**
     * Applique {@code request} a {@code application}. {@code request == null}
     * : NONE a la creation, inchange a la mise a jour. Leve
     * LoadConfigurationException (400) si un champ requis par le type manque.
     */
    public void apply(ApplicationAuthRequest request, Application application, boolean creating) {
        if (request == null) {
            if (creating) {
                application.setAuthType(ApplicationAuthType.NONE);
                application.setAuthConfig(null);
            }
            return;
        }
        ApplicationAuthType type = request.type();
        if (type == ApplicationAuthType.NONE) {
            application.setAuthType(ApplicationAuthType.NONE);
            application.setAuthConfig(null);
            return;
        }
        // Secrets conserves uniquement si le type ne change pas.
        ApplicationAuthConfig previous = type == application.getAuthType() ? read(application.getAuthConfig()) : ApplicationAuthConfig.empty();

        // Regles et messages IDENTIQUES a validateAuthForm (frontend,
        // ApplicationAuthFields.tsx) - toute modification doit etre faite
        // des deux cotes.
        ApplicationAuthConfig config = switch (type) {
            case BEARER_TOKEN -> {
                String token = keep(request.token(), previous.token());
                require(token, "Le jeton est obligatoire.");
                requireSingleLine(token, "Le jeton ne doit pas contenir de retour à la ligne.");
                yield new ApplicationAuthConfig(token.trim(), null, null, null, null, null, null, null, null, null, null);
            }
            case API_KEY -> {
                String name = trim(request.apiKeyName());
                ApiKeyLocation location = request.apiKeyLocation() != null ? request.apiKeyLocation() : ApiKeyLocation.HEADER;
                require(name, "Indiquez le nom de la clé (ex : X-API-Key).");
                if (location == ApiKeyLocation.HEADER && !HEADER_NAME.matcher(name).matches()) {
                    throw new LoadConfigurationException("Nom d'en-tête invalide (lettres, chiffres et - uniquement).");
                }
                if (location == ApiKeyLocation.QUERY && !QUERY_NAME.matcher(name).matches()) {
                    throw new LoadConfigurationException("Nom de paramètre invalide (pas d'espace ni de & = ? #).");
                }
                String value = keep(request.apiKeyValue(), previous.apiKeyValue());
                require(value, "La valeur de la clé est obligatoire.");
                requireSingleLine(value, "La clé ne doit pas contenir de retour à la ligne.");
                yield new ApplicationAuthConfig(null, name, value.trim(), location, null, null, null, null, null, null, null);
            }
            case BASIC -> {
                String username = trim(request.username());
                require(username, "Le nom d'utilisateur est obligatoire.");
                if (username.contains(":")) {
                    throw new LoadConfigurationException("Le nom d'utilisateur ne doit pas contenir « : ».");
                }
                requireSingleLine(username, "Le nom d'utilisateur ne doit pas contenir de retour à la ligne.");
                String password = keep(request.password(), previous.password());
                require(password, "Le mot de passe est obligatoire.");
                yield new ApplicationAuthConfig(null, null, null, null, username, password, null, null, null, null, null);
            }
            case LOGIN_TOKEN -> {
                String loginPath = trim(request.loginPath());
                require(loginPath, "Indiquez l'endpoint de connexion (ex : /api/auth/login).");
                if (WHITESPACE.matcher(loginPath).find()) {
                    throw new LoadConfigurationException("Le chemin ne doit pas contenir d'espace.");
                }
                if (notBlank(request.loginBody()) && !isJson(request.loginBody())) {
                    throw new LoadConfigurationException("Le corps doit être un JSON valide.");
                }
                String loginBody = keep(request.loginBody(), previous.loginBody());
                require(loginBody, "Indiquez les identifiants envoyés (JSON).");
                String tokenJsonPath = trim(request.tokenJsonPath());
                require(tokenJsonPath, "Indiquez où lire le jeton (ex : $.token).");
                if (!JSON_PATH.matcher(tokenJsonPath).matches()) {
                    throw new LoadConfigurationException("Chemin invalide (ex : $.token ou $.data.accessToken).");
                }
                String headerName = trim(request.tokenHeaderName());
                if (headerName == null) headerName = ApplicationAuthConfig.DEFAULT_TOKEN_HEADER;
                if (!HEADER_NAME.matcher(headerName).matches()) {
                    throw new LoadConfigurationException("Nom d'en-tête invalide (lettres, chiffres et - uniquement).");
                }
                // null = prefixe par defaut "Bearer " ; "" = jeton envoye brut.
                String prefix = request.tokenPrefix();
                if (prefix != null) requireSingleLine(prefix, "Le préfixe ne doit pas contenir de retour à la ligne.");
                yield new ApplicationAuthConfig(null, null, null, null, null, null, loginPath, loginBody, tokenJsonPath, headerName, prefix);
            }
            case NONE -> throw new IllegalStateException("NONE traite plus haut.");
        };
        if (type == ApplicationAuthType.BEARER_TOKEN || type == ApplicationAuthType.API_KEY
                || type == ApplicationAuthType.BASIC) {
            String verificationPath = trim(request.verificationPath());
            require(verificationPath, "Indiquez l'endpoint de vérification (ex : /api/account/balance).");
            if (WHITESPACE.matcher(verificationPath).find()) {
                throw new LoadConfigurationException("L'endpoint de vérification ne doit pas contenir d'espace.");
            }
            config = config.withVerificationPath(verificationPath);
        }
        application.setAuthType(type);
        application.setAuthConfig(write(config));
    }

    /** Vue SANS secret, utilisee par ApplicationMapper#toResponse. */
    @Named("applicationAuthResponse")
    public ApplicationAuthResponse toResponse(Application application) {
        ApplicationAuthType type = application.getAuthType() != null ? application.getAuthType() : ApplicationAuthType.NONE;
        ApplicationAuthConfig c = read(application.getAuthConfig());
        return new ApplicationAuthResponse(
                type,
                c.apiKeyName(),
                c.apiKeyLocation(),
                c.username(),
                c.loginPath(),
                c.tokenJsonPath(),
                c.tokenHeaderName(),
                c.tokenPrefix(),
                c.verificationPath(),
                notBlank(c.token()),
                notBlank(c.apiKeyValue()),
                notBlank(c.password()),
                notBlank(c.loginBody()));
    }

    public ApplicationAuthSpec toSpec(Application application) {
        if (application.getAuthType() == null || application.getAuthType() == ApplicationAuthType.NONE) {
            return ApplicationAuthSpec.none();
        }
        return new ApplicationAuthSpec(application.getAuthType(), read(application.getAuthConfig()));
    }

    /** Resolution reelle (connexion comprise pour LOGIN_TOKEN) - voir
     * ApplicationAuthenticator. Leve ApplicationAuthenticationException. */
    public ResolvedAuth resolve(Application application, long timeoutSeconds) {
        return authenticator.authenticate(toSpec(application), application.getUrl(), timeoutSeconds);
    }

    private ApplicationAuthConfig read(String json) {
        if (json == null || json.isBlank()) return ApplicationAuthConfig.empty();
        try {
            return objectMapper.readValue(json, ApplicationAuthConfig.class);
        } catch (JsonProcessingException e) {
            // Jamais le contenu (secrets) dans un message ou un log.
            throw new IllegalStateException("Configuration d'authentification illisible.");
        }
    }

    private String write(ApplicationAuthConfig config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Configuration d'authentification non serialisable.");
        }
    }

    private static String keep(String submitted, String previous) {
        return notBlank(submitted) ? submitted : previous;
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(String value, String message) {
        if (!notBlank(value)) throw new LoadConfigurationException(message);
    }

    private static void requireSingleLine(String value, String message) {
        if (LINE_BREAK.matcher(value).find()) throw new LoadConfigurationException(message);
    }

    private boolean isJson(String value) {
        try {
            objectMapper.readTree(value);
            return true;
        } catch (JsonProcessingException e) {
            return false;
        }
    }
}
