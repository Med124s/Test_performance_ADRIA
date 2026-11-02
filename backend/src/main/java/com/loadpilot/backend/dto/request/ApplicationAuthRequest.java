package com.loadpilot.backend.dto.request;

import com.loadpilot.backend.enums.ApiKeyLocation;
import com.loadpilot.backend.enums.ApplicationAuthType;
import com.loadpilot.backend.enums.CaptureSource;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Authentification d'une application ; seuls les champs du type choisi sont lus. Secret vide en mise a jour : valeur enregistree conservee. */
public record ApplicationAuthRequest(

        @NotNull(message = "Le type d'authentification est obligatoire.")
        ApplicationAuthType type,

        @Size(max = 4000, message = "Le jeton ne doit pas depasser 4000 caracteres.")
        String token,

        @Size(max = 255, message = "Le nom de la cle API ne doit pas depasser 255 caracteres.")
        String apiKeyName,

        @Size(max = 4000, message = "La cle API ne doit pas depasser 4000 caracteres.")
        String apiKeyValue,

        ApiKeyLocation apiKeyLocation,

        @Size(max = 255, message = "L'utilisateur ne doit pas depasser 255 caracteres.")
        String username,

        @Size(max = 1000, message = "Le mot de passe ne doit pas depasser 1000 caracteres.")
        String password,

        @Size(max = 1000, message = "Le chemin de connexion ne doit pas depasser 1000 caracteres.")
        String loginPath,

        @Size(max = 4000, message = "Le corps de connexion ne doit pas depasser 4000 caracteres.")
        String loginBody,

        @Size(max = 500, message = "Le chemin du jeton ne doit pas depasser 500 caracteres.")
        String tokenJsonPath,

        @Size(max = 255, message = "Le nom d'en-tete ne doit pas depasser 255 caracteres.")
        String tokenHeaderName,

        @Size(max = 50, message = "Le prefixe du jeton ne doit pas depasser 50 caracteres.")
        String tokenPrefix,

        /** Endpoint protege utilise par le test de connectivite (BEARER_TOKEN, API_KEY, BASIC). */
        @Size(max = 1000, message = "L'endpoint de verification ne doit pas depasser 1000 caracteres.")
        String verificationPath,

        /** LOGIN_TOKEN : JSON (defaut) = tokenJsonPath est un chemin dans le corps ; HEADER = nom de l'en-tete de reponse. */
        CaptureSource tokenSource
) {

    public ApplicationAuthRequest(ApplicationAuthType type, String token, String apiKeyName, String apiKeyValue,
            ApiKeyLocation apiKeyLocation, String username, String password, String loginPath, String loginBody,
            String tokenJsonPath, String tokenHeaderName, String tokenPrefix) {
        this(type, token, apiKeyName, apiKeyValue, apiKeyLocation, username, password, loginPath, loginBody,
                tokenJsonPath, tokenHeaderName, tokenPrefix, null, null);
    }

    public ApplicationAuthRequest(ApplicationAuthType type, String token, String apiKeyName, String apiKeyValue,
            ApiKeyLocation apiKeyLocation, String username, String password, String loginPath, String loginBody,
            String tokenJsonPath, String tokenHeaderName, String tokenPrefix, String verificationPath) {
        this(type, token, apiKeyName, apiKeyValue, apiKeyLocation, username, password, loginPath, loginBody,
                tokenJsonPath, tokenHeaderName, tokenPrefix, verificationPath, null);
    }
}
