package com.loadpilot.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.dto.response.IntegrationsResponse;
import com.loadpilot.backend.dto.response.IntegrationsResponse.AuthMail;
import com.loadpilot.backend.dto.response.IntegrationsResponse.Mail;
import com.loadpilot.backend.dto.response.IntegrationsResponse.Monitoring;
import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.MonitoringType;
import com.loadpilot.backend.repository.ApplicationRepository;
import com.loadpilot.backend.service.audit.AuditEvent;
import com.loadpilot.backend.service.audit.AuditTargetType;
import com.loadpilot.backend.service.keycloak.KeycloakAdminClient;
import com.loadpilot.backend.service.keycloak.KeycloakSmtpInfo;
import com.loadpilot.backend.service.mail.MailService;
import com.loadpilot.backend.service.monitoring.MonitoringException;
import com.loadpilot.backend.service.monitoring.MonitoringSupport;
import com.loadpilot.backend.service.monitoring.ResourceProbe;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Etat des integrations (Parametres > Integrations) : serveur e-mail de LoadPilot (connexion SMTP
 * reelle, sans envoi), serveur e-mail du systeme d'authentification (lu, sans secret) et
 * monitoring CPU / RAM de chaque application (une lecture reelle, en parallele, 3 s max chacune).
 */
@Service
@RequiredArgsConstructor
public class IntegrationsService {

    private final MailService mailService;
    private final KeycloakAdminClient keycloak;
    private final ApplicationRepository applicationRepository;
    private final MonitoringSupport monitoringSupport;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public IntegrationsResponse integrations() {
        CompletableFuture<Mail> mail = CompletableFuture.supplyAsync(this::mail);
        CompletableFuture<AuthMail> authMail = CompletableFuture.supplyAsync(this::authMail);
        List<CompletableFuture<Monitoring>> monitoring = applicationRepository.findAll().stream()
                .filter(a -> a.getMonitoringType() != null && a.getMonitoringType() != MonitoringType.NONE)
                .sorted(Comparator.comparing(Application::isArchived).thenComparing(Application::getName, String.CASE_INSENSITIVE_ORDER))
                .map(a -> CompletableFuture.supplyAsync(() -> monitoring(a)))
                .toList();
        return new IntegrationsResponse(mail.join(), authMail.join(), monitoring.stream().map(CompletableFuture::join).toList());
    }

    private Mail mail() {
        MailService.MailServerInfo i = mailService.info();
        return new Mail(i.enabled(), i.kind(), i.host(), i.port(), i.from(), i.fromName(), i.authenticated(), i.reachable(), i.message());
    }

    private AuthMail authMail() {
        try {
            KeycloakSmtpInfo smtp = keycloak.smtpInfo();
            return smtp == null ? null : new AuthMail(smtp.host(), smtp.port(), smtp.from(), smtp.fromName(), smtp.authenticated());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Monitoring monitoring(Application a) {
        String endpoint = withoutSecrets(a.getMonitoringUrl());
        try {
            new ResourceProbe(monitoringSupport.toConfig(a), httpClient, objectMapper).read();
            return new Monitoring(a.getId().toString(), a.getName(), a.isArchived(), a.getMonitoringType().name(), endpoint, true, null);
        } catch (MonitoringException e) {
            return new Monitoring(a.getId().toString(), a.getName(), a.isArchived(), a.getMonitoringType().name(), endpoint, false, e.getMessage());
        } catch (RuntimeException e) {
            return new Monitoring(a.getId().toString(), a.getName(), a.isArchived(), a.getMonitoringType().name(), endpoint, false,
                    "Lecture impossible.");
        }
    }

    /** Adresse sans identifiants (user:pass@) ni parametres (?token=...). */
    static String withoutSecrets(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI u = URI.create(url.trim());
            if (u.getHost() == null) return null;
            return u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "") + (u.getRawPath() == null ? "" : u.getRawPath());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** E-mail de test a l'adresse du compte connecte ; journalise. Leve MailService.MailException si l'envoi echoue. */
    public String sendTestEmail(String to, String username) {
        try {
            mailService.send(to, "[LoadPilot] E-mail de test",
                    "Bonjour" + (username == null ? "" : " " + username) + ",\n\nCet e-mail de test confirme que LoadPilot peut envoyer des e-mails "
                            + "(notifications de fin d’exécution, d’échec, de régression et de planification).\n\n—\nLoadPilot");
            auditLogService.record(AuditEvent.of(AuditAction.TEST, AuditModule.SETTINGS)
                    .target(AuditTargetType.SETTINGS, "mail", "Serveur e-mail")
                    .description("E-mail de test envoyé à " + to));
            return to;
        } catch (MailService.MailException e) {
            auditLogService.record(AuditEvent.of(AuditAction.TEST, AuditModule.SETTINGS)
                    .target(AuditTargetType.SETTINGS, "mail", "Serveur e-mail")
                    .description("E-mail de test non envoyé").failed(e.getMessage()));
            throw e;
        }
    }
}
