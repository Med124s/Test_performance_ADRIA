package com.loadpilot.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolution de l'IP reelle du client d'une requete (journal d'audit).
 *
 * Par defaut : l'adresse du PAIR TCP direct ({@link HttpServletRequest#getRemoteAddr()}),
 * qui est bien l'IP du client sans reverse proxy.
 *
 * Derriere un reverse proxy : declarer son adresse dans
 * {@code app.audit.trusted-proxies} (ex. "10.0.0.5,127.0.0.1"). Seulement
 * dans ce cas, l'en-tete X-Forwarded-For est lu, de droite a gauche, en
 * ignorant les proxies de confiance : la premiere adresse non fiable est celle
 * du client. Un client qui forge lui-meme X-Forwarded-For sans passer par un
 * proxy declare ne peut donc jamais inscrire une fausse IP dans l'audit.
 */
@Component
public class RequestIpResolver {

    private final Set<String> trustedProxies;

    public RequestIpResolver(@Value("${app.audit.trusted-proxies:}") List<String> trustedProxies) {
        this.trustedProxies = trustedProxies == null ? Set.of()
                : trustedProxies.stream().map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String remote = request.getRemoteAddr();
        if (trustedProxies.isEmpty() || !trustedProxies.contains(remote)) {
            return remote;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return remote;
        }
        String[] hops = forwarded.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (!hop.isEmpty() && !trustedProxies.contains(hop)) {
                return hop.length() > 45 ? hop.substring(0, 45) : hop;
            }
        }
        return remote;
    }
}
