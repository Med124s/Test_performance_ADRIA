package com.loadpilot.backend.dto.request;

import com.loadpilot.backend.enums.ProxyMode;
import jakarta.validation.constraints.Size;

/**
 * Reseau (optionnel) pour joindre l'application : proxy global (INHERIT, defaut), aucun (NONE) ou
 * propre a l'application (CUSTOM). Regles verifiees par NetworkSupport (400 explicite).
 *
 * SECRET (proxyPassword) : comme pour l'authentification, vide ou absent en mise a jour = mot de passe
 * deja enregistre conserve (l'API ne le renvoie jamais).
 */
public record ApplicationNetworkRequest(
        ProxyMode proxyMode,

        @Size(max = 255, message = "L'hôte du proxy ne doit pas dépasser 255 caractères.")
        String proxyHost,

        Integer proxyPort,

        @Size(max = 255, message = "L'identifiant du proxy ne doit pas dépasser 255 caractères.")
        String proxyUsername,

        @Size(max = 1000, message = "Le mot de passe du proxy ne doit pas dépasser 1000 caractères.")
        String proxyPassword,

        /** Autorite(s) de certification au format PEM, ajoutees a la confiance de la JVM pour cette
         * application seulement ; vide = confiance de la JVM seule (voir TlsTrust). */
        @Size(max = 65_536, message = "L'autorité de certification ne doit pas dépasser 64 Ko.")
        String tlsCaCertificate
) {
    /** Compatibilite : reseau sans autorite de certification propre. */
    public ApplicationNetworkRequest(ProxyMode proxyMode, String proxyHost, Integer proxyPort, String proxyUsername,
            String proxyPassword) {
        this(proxyMode, proxyHost, proxyPort, proxyUsername, proxyPassword, null);
    }
}
