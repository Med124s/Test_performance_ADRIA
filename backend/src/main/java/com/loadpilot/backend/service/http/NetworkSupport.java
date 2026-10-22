package com.loadpilot.backend.service.http;

import com.loadpilot.backend.dto.request.ApplicationNetworkRequest;
import com.loadpilot.backend.dto.response.ApplicationNetworkResponse;
import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.enums.ProxyMode;
import com.loadpilot.backend.exception.BadRequestException;
import org.mapstruct.Named;
import org.springframework.stereotype.Component;

/**
 * Reseau d'une Application (proxy) : validation et enregistrement de la saisie, profil effectif
 * (NetworkProfile), vue sans le mot de passe. Meme principe que MonitoringSupport.
 */
@Component
public class NetworkSupport {

    /** Mode d'une NOUVELLE application quand la creation n'en precise pas : aucun proxy. */
    public static final ProxyMode CREATION_DEFAULT_MODE = ProxyMode.NONE;

    /**
     * Creation : saisie appliquee, ou « Aucun proxy » si la requete n'a pas de section reseau ou pas de
     * mode. Seules les nouvelles applications sont concernees : une application existante dont le mode
     * est vide (NULL en base) reste lue comme « Proxy du serveur » (voir toResponse, NetworkProfile.of).
     */
    public void applyOnCreate(ApplicationNetworkRequest request, Application application) {
        if (request == null) {
            application.setProxyMode(CREATION_DEFAULT_MODE);
            return;
        }
        apply(request, application, CREATION_DEFAULT_MODE);
    }

    /** Mise a jour : saisie appliquee (null = inchangee). Mot de passe vide = mot de passe deja enregistre conserve. */
    public void apply(ApplicationNetworkRequest request, Application application) {
        apply(request, application, ProxyMode.INHERIT);
    }

    /** defaultMode : mode retenu si la saisie n'en precise pas. */
    private void apply(ApplicationNetworkRequest request, Application application, ProxyMode defaultMode) {
        if (request == null) return;
        // Confiance TLS : independante du proxy ; vide = confiance de la JVM seule.
        String ca = blankToNull(request.tlsCaCertificate());
        String caProblem = TlsTrust.problem(ca);
        if (caProblem != null) throw new BadRequestException(caProblem);
        application.setTlsCaCertificate(ca);
        ProxyMode mode = request.proxyMode() == null ? defaultMode : request.proxyMode();
        if (mode != ProxyMode.CUSTOM) {
            application.setProxyMode(mode);
            application.setProxyHost(null);
            application.setProxyPort(null);
            application.setProxyUsername(null);
            application.setProxyPassword(null);
            return;
        }
        String host = blankToNull(request.proxyHost());
        String username = blankToNull(request.proxyUsername());
        String password = password(request, application);
        validate(host, request.proxyPort(), username);
        application.setProxyMode(ProxyMode.CUSTOM);
        application.setProxyHost(host);
        application.setProxyPort(request.proxyPort());
        application.setProxyUsername(username);
        // Sans identifiant, aucun mot de passe n'est garde.
        application.setProxyPassword(username == null ? null : password);
    }

    /**
     * Profil effectif d'une saisie (« Tester le monitoring » avant enregistrement) ; existing
     * (optionnel) fournit le mot de passe deja enregistre si le champ est laisse vide.
     */
    public NetworkProfile toProfile(ApplicationNetworkRequest request, Application existing) {
        String caProblem = TlsTrust.problem(request.tlsCaCertificate());
        if (caProblem != null) throw new BadRequestException(caProblem);
        return proxyProfile(request, existing).withTrustedAuthorities(request.tlsCaCertificate());
    }

    private NetworkProfile proxyProfile(ApplicationNetworkRequest request, Application existing) {
        ProxyMode mode = request.proxyMode() == null ? ProxyMode.INHERIT : request.proxyMode();
        return switch (mode) {
            case INHERIT -> NetworkProfile.inherit();
            case NONE -> NetworkProfile.direct();
            case CUSTOM -> {
                String host = blankToNull(request.proxyHost());
                String username = blankToNull(request.proxyUsername());
                validate(host, request.proxyPort(), username);
                yield NetworkProfile.custom(host, request.proxyPort(), username, password(request, existing));
            }
        };
    }

    @Named("applicationNetworkResponse")
    public ApplicationNetworkResponse toResponse(Application application) {
        ProxyMode mode = application.getProxyMode() == null ? ProxyMode.INHERIT : application.getProxyMode();
        return new ApplicationNetworkResponse(mode, application.getProxyHost(), application.getProxyPort(),
                application.getProxyUsername(), application.getProxyPassword() != null && !application.getProxyPassword().isBlank(),
                application.getTlsCaCertificate(), TlsTrust.describe(application.getTlsCaCertificate()));
    }

    private static String password(ApplicationNetworkRequest request, Application existing) {
        String typed = request.proxyPassword();
        if (typed != null && !typed.isEmpty()) return typed;
        return existing != null && existing.getProxyMode() == ProxyMode.CUSTOM ? existing.getProxyPassword() : null;
    }

    private static void validate(String host, Integer port, String username) {
        String problem = NetworkProfile.proxyProblem(host, port, username);
        if (problem != null) throw new BadRequestException(problem);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
