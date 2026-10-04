package com.loadpilot.backend.service.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.loadpilot.backend.enums.ApiKeyLocation;

/**
 * Parametres d'authentification d'une Application, persistes en JSON dans
 * application.auth_config. Seuls les champs du type choisi sont renseignes
 * (les autres restent null et ne sont pas serialises).
 *
 * SECRETS : token, apiKeyValue, password, loginBody - jamais renvoyes par
 * l'API (voir ApplicationAuthSupport#toResponse).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApplicationAuthConfig(
        // BEARER_TOKEN
        String token,
        // API_KEY
        String apiKeyName,
        String apiKeyValue,
        ApiKeyLocation apiKeyLocation,
        // BASIC
        String username,
        String password,
        // LOGIN_TOKEN
        String loginPath,
        String loginBody,
        String tokenJsonPath,
        String tokenHeaderName,
        String tokenPrefix,
        // BEARER_TOKEN / API_KEY / BASIC : endpoint protege utilise par le
        // test de connectivite pour verifier reellement les identifiants.
        String verificationPath
) {

    public static final String DEFAULT_TOKEN_HEADER = "Authorization";
    public static final String DEFAULT_TOKEN_PREFIX = "Bearer ";

    /** Compatibilite : configuration sans endpoint de verification. */
    public ApplicationAuthConfig(String token, String apiKeyName, String apiKeyValue, ApiKeyLocation apiKeyLocation,
            String username, String password, String loginPath, String loginBody, String tokenJsonPath,
            String tokenHeaderName, String tokenPrefix) {
        this(token, apiKeyName, apiKeyValue, apiKeyLocation, username, password, loginPath, loginBody, tokenJsonPath,
                tokenHeaderName, tokenPrefix, null);
    }

    public static ApplicationAuthConfig empty() {
        return new ApplicationAuthConfig(null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public ApplicationAuthConfig withVerificationPath(String path) {
        return new ApplicationAuthConfig(token, apiKeyName, apiKeyValue, apiKeyLocation, username, password, loginPath,
                loginBody, tokenJsonPath, tokenHeaderName, tokenPrefix, path);
    }
}
