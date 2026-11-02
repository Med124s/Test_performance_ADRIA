package com.loadpilot.backend.service.auth;

import com.loadpilot.backend.enums.ApplicationAuthType;

/** Authentification a appliquer aux requetes, copiee au lancement. */
public record ApplicationAuthSpec(ApplicationAuthType type, ApplicationAuthConfig config) {

    public static ApplicationAuthSpec none() {
        return new ApplicationAuthSpec(ApplicationAuthType.NONE, ApplicationAuthConfig.empty());
    }

    public boolean isNone() {
        return type == null || type == ApplicationAuthType.NONE;
    }
}
