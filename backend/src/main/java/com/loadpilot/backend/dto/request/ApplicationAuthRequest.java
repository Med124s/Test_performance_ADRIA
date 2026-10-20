package com.loadpilot.backend.dto.request;

import com.loadpilot.backend.enums.ApiKeyLocation;
import com.loadpilot.backend.enums.ApplicationAuthType;
import com.loadpilot.backend.enums.CaptureSource;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Authentification d'une Application. Seuls les champs du type choisi sont
 * lus ; les regles propres a chaque type sont verifiees par
 * ApplicationAuthSupport (400 explicite).
 *
 * SECRETS (token, apiKeyValue, password, loginBody) : en mise a jour, un
 * champ secret vide ou absent CONSERVE la valeur deja enregistree pour ce
 * meme type - l'API ne renvoyant jamais les secrets, le formulaire ne peut
 * pas les re-soumettre.
 */
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

        /** Obligatoire pour BEARER_TOKEN, API_KEY et BASIC : endpoint protege
         * (relatif a l'URL de l'application ou absolu) sur lequel le test de
         * connectivite verifie reellement les identifiants. */
        @Size(max = 1000, message = "L'endpoint de verification ne doit pas depasser 1000 caracteres.")
        String verificationPath,

        /** LOGIN_TOKEN : JSON (defaut, null) = tokenJsonPath est un chemin dans le corps ; HEADER =
         * tokenJsonPath est le nom de l'en-tete de reponse qui contient le jeton. */
        CaptureSource tokenSource
) {

    /** Compatibilite : requete sans endpoint de verification. */
    public ApplicationAuthRequest(ApplicationAuthType type, String token, String apiKeyName, String apiKeyValue,
            ApiKeyLocation apiKeyLocation, String username, String password, String loginPath, String loginBody,
            String tokenJsonPath, String tokenHeaderName, String tokenPrefix) {
        this(type, token, apiKeyName, apiKeyValue, apiKeyLocation, username, password, loginPath, loginBody,
                tokenJsonPath, tokenHeaderName, tokenPrefix, null, null);
    }

    /** Compatibilite : jeton lu dans le corps JSON (source non precisee). */
    public ApplicationAuthRequest(ApplicationAuthType type, String token, String apiKeyName, String apiKeyValue,
            ApiKeyLocation apiKeyLocation, String username, String password, String loginPath, String loginBody,
            String tokenJsonPath, String tokenHeaderName, String tokenPrefix, String verificationPath) {
        this(type, token, apiKeyName, apiKeyValue, apiKeyLocation, username, password, loginPath, loginBody,
                tokenJsonPath, tokenHeaderName, tokenPrefix, verificationPath, null);
    }
}
