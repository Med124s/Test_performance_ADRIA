package com.loadpilot.backend.dto.request;

import com.loadpilot.backend.enums.ProxyMode;
import jakarta.validation.constraints.Size;

/** Reseau pour joindre l'application : proxy global (INHERIT), aucun (NONE) ou propre a l'application (CUSTOM). */
public record ApplicationNetworkRequest(
        ProxyMode proxyMode,

        @Size(max = 255, message = "L'hôte du proxy ne doit pas dépasser 255 caractères.")
        String proxyHost,

        Integer proxyPort,

        @Size(max = 255, message = "L'identifiant du proxy ne doit pas dépasser 255 caractères.")
        String proxyUsername,

        @Size(max = 1000, message = "Le mot de passe du proxy ne doit pas dépasser 1000 caractères.")
        String proxyPassword,

        /** Autorites de certification (PEM) ajoutees a la confiance de la JVM pour cette application ; vide = JVM seule. */
        @Size(max = 65_536, message = "L'autorité de certification ne doit pas dépasser 64 Ko.")
        String tlsCaCertificate
) {
    public ApplicationNetworkRequest(ProxyMode proxyMode, String proxyHost, Integer proxyPort, String proxyUsername,
            String proxyPassword) {
        this(proxyMode, proxyHost, proxyPort, proxyUsername, proxyPassword, null);
    }
}
