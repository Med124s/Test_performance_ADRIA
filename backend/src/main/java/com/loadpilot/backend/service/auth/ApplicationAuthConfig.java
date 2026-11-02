package com.loadpilot.backend.service.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.loadpilot.backend.enums.ApiKeyLocation;
import com.loadpilot.backend.enums.CaptureSource;

/** Parametres d'authentification d'une application, stockes en JSON ; seuls les champs du type choisi sont renseignes. */
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
        /** Endpoint protege utilise par le test de connectivite. */
        String verificationPath,
        /** LOGIN_TOKEN : ou lire le jeton - JSON (defaut) = chemin dans le corps ; HEADER = nom d'en-tete de reponse (ou "Set-Cookie:NOM"). */
        CaptureSource tokenSource
) {

    public static final String DEFAULT_TOKEN_HEADER = "Authorization";
    public static final String DEFAULT_TOKEN_PREFIX = "Bearer ";

    public ApplicationAuthConfig(String token, String apiKeyName, String apiKeyValue, ApiKeyLocation apiKeyLocation,
            String username, String password, String loginPath, String loginBody, String tokenJsonPath,
            String tokenHeaderName, String tokenPrefix) {
        this(token, apiKeyName, apiKeyValue, apiKeyLocation, username, password, loginPath, loginBody, tokenJsonPath,
                tokenHeaderName, tokenPrefix, null, null);
    }

    public ApplicationAuthConfig(String token, String apiKeyName, String apiKeyValue, ApiKeyLocation apiKeyLocation,
            String username, String password, String loginPath, String loginBody, String tokenJsonPath,
            String tokenHeaderName, String tokenPrefix, String verificationPath) {
        this(token, apiKeyName, apiKeyValue, apiKeyLocation, username, password, loginPath, loginBody, tokenJsonPath,
                tokenHeaderName, tokenPrefix, verificationPath, null);
    }

    public static ApplicationAuthConfig empty() {
        return new ApplicationAuthConfig(null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public ApplicationAuthConfig withVerificationPath(String path) {
        return new ApplicationAuthConfig(token, apiKeyName, apiKeyValue, apiKeyLocation, username, password, loginPath,
                loginBody, tokenJsonPath, tokenHeaderName, tokenPrefix, path, tokenSource);
    }

    /** Jeton lu dans un en-tete de reponse plutot que dans le corps. */
    public boolean tokenFromHeader() {
        return tokenSource == CaptureSource.HEADER;
    }
}
