package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.ProxyMode;
import com.loadpilot.backend.service.http.TlsTrust;
import java.util.List;

/**
 * Reseau d'une application, sans le mot de passe du proxy (simple indicateur « configuré »).
 * tlsCaCertificate : certificats publics (PEM), renvoyes tels quels ; tlsCaCertificates : leur resume.
 */
public record ApplicationNetworkResponse(
        ProxyMode proxyMode,
        String proxyHost,
        Integer proxyPort,
        String proxyUsername,
        boolean proxyPasswordConfigured,
        String tlsCaCertificate,
        List<TlsTrust.CertificateInfo> tlsCaCertificates
) {
    /** Compatibilite : reseau sans autorite de certification propre. */
    public ApplicationNetworkResponse(ProxyMode proxyMode, String proxyHost, Integer proxyPort, String proxyUsername,
            boolean proxyPasswordConfigured) {
        this(proxyMode, proxyHost, proxyPort, proxyUsername, proxyPasswordConfigured, null, List.of());
    }
}
