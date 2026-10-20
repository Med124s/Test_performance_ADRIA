package com.loadpilot.backend.service.monitoring;

import com.loadpilot.backend.dto.request.ApplicationMonitoringRequest;
import com.loadpilot.backend.dto.response.ApplicationMonitoringResponse;
import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.enums.MonitoringAuthType;
import com.loadpilot.backend.enums.MonitoringType;
import com.loadpilot.backend.exception.BadRequestException;
import java.net.URI;
import org.mapstruct.Named;
import org.springframework.stereotype.Component;

/**
 * Monitoring d'une Application : validation et enregistrement de la configuration saisie, vue
 * sans secret, configuration effective pour les lectures.
 */
@Component
public class MonitoringSupport {

    static final int DEFAULT_INTERVAL_SECONDS = 5;

    /** Applique la configuration (null = inchangee). Secret vide = secret deja enregistre conserve (meme authentification). */
    public void apply(ApplicationMonitoringRequest request, Application application) {
        if (request == null) return;
        // « Monitoring sans proxy » : null = inchange (reglage garde meme si le monitoring est desactive).
        if (request.withoutProxy() != null) application.setMonitoringWithoutProxy(request.withoutProxy());
        MonitoringType type = request.type() == null ? MonitoringType.NONE : request.type();
        if (type == MonitoringType.NONE) {
            application.setMonitoringType(MonitoringType.NONE);
            application.setMonitoringUrl(blankToNull(request.url()));
            application.setMonitoringAuthType(MonitoringAuthType.NONE);
            application.setMonitoringHeaderName(null);
            application.setMonitoringSecret(null);
            application.setMonitoringIntervalSeconds(interval(request.intervalSeconds()));
            return;
        }
        MonitoringConfig config = toConfig(request, application);
        application.setMonitoringType(config.type());
        application.setMonitoringUrl(config.url());
        application.setMonitoringAuthType(config.authType());
        application.setMonitoringHeaderName(config.headerName());
        application.setMonitoringSecret(config.secret());
        application.setMonitoringIntervalSeconds(config.intervalSeconds());
    }

    /**
     * Configuration effective et VALIDEE d'une saisie (enregistrement ou « Tester le monitoring ») ;
     * existing (optionnel) fournit le secret deja enregistre si le champ est laisse vide.
     */
    public MonitoringConfig toConfig(ApplicationMonitoringRequest request, Application existing) {
        MonitoringType type = request.type() == null ? MonitoringType.NONE : request.type();
        String url = blankToNull(request.url());
        if (type != MonitoringType.NONE) {
            if (url == null) throw new BadRequestException("Indiquez l'URL du monitoring (ex : http://localhost:8888/metrics).");
            if (!isHttpUrl(url)) throw new BadRequestException("URL de monitoring invalide : elle doit commencer par http:// ou https://.");
        }
        MonitoringAuthType authType = request.authType() == null ? MonitoringAuthType.NONE : request.authType();
        String headerName = authType == MonitoringAuthType.HEADER ? blankToNull(request.headerName()) : null;
        if (authType == MonitoringAuthType.HEADER) {
            if (headerName == null) throw new BadRequestException("Indiquez le nom de l'en-tête d'authentification du monitoring.");
            if (!headerName.matches("[A-Za-z0-9!#$%&'*+.^_`|~-]+")) {
                throw new BadRequestException("Nom d'en-tête invalide pour le monitoring : " + headerName + ".");
            }
        }
        String secret = blankToNull(request.secret());
        if (secret == null && authType != MonitoringAuthType.NONE && existing != null && existing.getMonitoringAuthType() == authType) {
            secret = existing.getMonitoringSecret();
        }
        if (authType != MonitoringAuthType.NONE && secret == null) {
            throw new BadRequestException(authType == MonitoringAuthType.BEARER
                    ? "Indiquez le jeton du monitoring." : "Indiquez la valeur de l'en-tête du monitoring.");
        }
        return new MonitoringConfig(type, url, authType, headerName, authType == MonitoringAuthType.NONE ? null : secret,
                interval(request.intervalSeconds()));
    }

    /** Configuration enregistree d'une application (enabled() = false si aucun monitoring). */
    public MonitoringConfig toConfig(Application application) {
        return new MonitoringConfig(application.getMonitoringType(), application.getMonitoringUrl(), application.getMonitoringAuthType(),
                application.getMonitoringHeaderName(), application.getMonitoringSecret(),
                application.getMonitoringIntervalSeconds() <= 0 ? DEFAULT_INTERVAL_SECONDS : application.getMonitoringIntervalSeconds(),
                com.loadpilot.backend.service.http.NetworkProfile.forMonitoring(application));
    }

    @Named("applicationMonitoringResponse")
    public ApplicationMonitoringResponse toResponse(Application application) {
        return new ApplicationMonitoringResponse(
                application.getMonitoringType() == null ? MonitoringType.NONE : application.getMonitoringType(),
                application.getMonitoringUrl(),
                application.getMonitoringAuthType() == null ? MonitoringAuthType.NONE : application.getMonitoringAuthType(),
                application.getMonitoringHeaderName(),
                application.getMonitoringSecret() != null && !application.getMonitoringSecret().isBlank(),
                application.getMonitoringIntervalSeconds() <= 0 ? DEFAULT_INTERVAL_SECONDS : application.getMonitoringIntervalSeconds(),
                Boolean.TRUE.equals(application.getMonitoringWithoutProxy()));
    }

    private static int interval(Integer seconds) {
        return seconds == null ? DEFAULT_INTERVAL_SECONDS : Math.max(1, Math.min(60, seconds));
    }

    private static boolean isHttpUrl(String url) {
        try {
            URI uri = URI.create(url);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
