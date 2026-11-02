package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.ApiKeyLocation;
import com.loadpilot.backend.enums.ApplicationAuthType;

/** Authentification d'une application, sans les secrets (remplaces par un indicateur "configure"). */
public record ApplicationAuthResponse(
        ApplicationAuthType type,
        String apiKeyName,
        ApiKeyLocation apiKeyLocation,
        String username,
        String loginPath,
        String tokenJsonPath,
        /** null = JSON. */
        com.loadpilot.backend.enums.CaptureSource tokenSource,
        String tokenHeaderName,
        String tokenPrefix,
        String verificationPath,
        boolean tokenConfigured,
        boolean apiKeyValueConfigured,
        boolean passwordConfigured,
        boolean loginBodyConfigured
) {
}
