package com.loadpilot.backend.service.audit;

import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.repository.AuditLogRepository;
import com.loadpilot.backend.service.AppSettingsService;
import com.loadpilot.backend.service.AuditLogService;
import com.loadpilot.backend.service.keycloak.KeycloakAdminClient;
import com.loadpilot.backend.service.keycloak.KeycloakSecurityEvent;
import com.loadpilot.backend.service.keycloak.KeycloakUserRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Importe periodiquement (60 s par defaut) les evenements de securite de Keycloak dans le journal d'audit. */
@Component
@RequiredArgsConstructor
public class SecurityEventImporter {

    private static final Logger log = LoggerFactory.getLogger(SecurityEventImporter.class);
    private static final int PAGE = 200;
    private static final int MAX_PER_RUN = 5_000;
    static final String SOURCE = "Authentification";

    private final KeycloakAdminClient keycloakAdminClient;
    private final AuditLogService auditLogService;
    private final AuditLogRepository auditLogRepository;
    private final AppSettingsService settings;

    @Value("${app.audit.security-events.enabled:true}")
    private boolean enabled;

    @Scheduled(initialDelayString = "${app.audit.security-events.initial-delay-ms:20000}",
            fixedDelayString = "${app.audit.security-events.poll-ms:60000}")
    public void scheduledImport() {
        if (!enabled) {
            return;
        }
        try {
            importNewEvents();
        } catch (Exception e) {
            log.warn("Import des evenements de securite impossible : {}", e.getMessage());
        }
    }

    /** Importe les evenements posterieurs au curseur ; renvoie le nombre d'entrees ajoutees. */
    public int importNewEvents() {
        long cursor = settings.getLong(AppSettingsService.SECURITY_EVENTS_CURSOR, 0L);
        List<KeycloakSecurityEvent> fresh = new ArrayList<>();
        for (int first = 0; first < MAX_PER_RUN; first += PAGE) {
            List<KeycloakSecurityEvent> page = keycloakAdminClient.securityEvents(first, PAGE);
            boolean reachedCursor = false;
            for (KeycloakSecurityEvent event : page) {
                // ">=" : evenements de la meme milliseconde que le curseur, dedoublonnes par external_id.
                if (event.time() >= cursor) {
                    fresh.add(event);
                } else {
                    reachedCursor = true;
                }
            }
            if (reachedCursor || page.size() < PAGE) {
                break;
            }
        }
        if (fresh.isEmpty()) {
            return 0;
        }
        fresh.sort(Comparator.comparingLong(KeycloakSecurityEvent::time));
        Map<String, String> usernames = usernamesById();
        int imported = 0;
        long newCursor = cursor;
        for (KeycloakSecurityEvent event : fresh) {
            newCursor = Math.max(newCursor, event.time());
            if (event.id() == null || auditLogRepository.existsByExternalId(event.id())) {
                continue;
            }
            AuditEvent auditEvent = toAuditEvent(event, usernames);
            if (auditEvent != null) {
                auditLogService.record(auditEvent);
                imported++;
            }
        }
        settings.set(AppSettingsService.SECURITY_EVENTS_CURSOR, String.valueOf(newCursor), "Système");
        return imported;
    }

    private Map<String, String> usernamesById() {
        Map<String, String> map = new HashMap<>();
        try {
            for (KeycloakUserRecord user : keycloakAdminClient.listUsers()) {
                map.put(user.id(), user.username());
            }
        } catch (Exception e) {
            log.debug("Noms d'utilisateur indisponibles pour l'import : {}", e.getMessage());
        }
        return map;
    }

    /** Traduction d'un evenement Keycloak en entree du journal (null = type ignore). */
    static AuditEvent toAuditEvent(KeycloakSecurityEvent event, Map<String, String> usernames) {
        String account = event.userId() != null ? usernames.getOrDefault(event.userId(), event.typedUsername()) : event.typedUsername();
        String typed = event.typedUsername();
        AuditEvent audit = switch (event.type()) {
            case "LOGIN" -> AuditEvent.of(AuditAction.LOGIN, AuditModule.SECURITY).description("Connexion réussie");
            case "LOGOUT" -> AuditEvent.of(AuditAction.LOGOUT, AuditModule.SECURITY).description("Déconnexion");
            case "LOGIN_ERROR" -> loginError(event.error(), typed);
            case "USER_DISABLED_BY_TEMPORARY_LOCKOUT" -> AuditEvent.of(AuditAction.ACCOUNT_LOCKED, AuditModule.SECURITY)
                    .result(AuditResult.FAILURE).description("Compte bloqué temporairement après plusieurs mots de passe erronés");
            case "USER_DISABLED_BY_PERMANENT_LOCKOUT" -> AuditEvent.of(AuditAction.ACCOUNT_LOCKED, AuditModule.SECURITY)
                    .result(AuditResult.FAILURE).description("Compte bloqué après trop de mots de passe erronés (déblocage par un administrateur)");
            case "UPDATE_PASSWORD" -> AuditEvent.of(AuditAction.PASSWORD_CHANGED, AuditModule.SECURITY)
                    .description("Mot de passe changé par l’utilisateur");
            case "RESET_PASSWORD" -> AuditEvent.of(AuditAction.PASSWORD_CHANGED, AuditModule.SECURITY)
                    .description("Nouveau mot de passe choisi via le lien de réinitialisation");
            case "SEND_RESET_PASSWORD" -> AuditEvent.of(AuditAction.PASSWORD_RESET_LINK, AuditModule.SECURITY)
                    .description("Lien de réinitialisation demandé (« Mot de passe oublié »)");
            default -> null;
        };
        if (audit == null) {
            return null;
        }
        if (event.userId() != null && account != null) {
            audit.target(AuditTargetType.USER, event.userId(), account);
        }
        return audit.imported(event.id(), Instant.ofEpochMilli(event.time()), event.userId(), account, event.ipAddress(), SOURCE);
    }

    private static AuditEvent loginError(String error, String typed) {
        String name = typed != null && !typed.isBlank() ? " (nom saisi : " + typed + ")" : "";
        if ("user_temporarily_disabled".equals(error)) {
            return AuditEvent.of(AuditAction.LOGIN_FAILED, AuditModule.SECURITY).result(AuditResult.FAILURE)
                    .description("Connexion refusée : compte bloqué temporairement" + name);
        }
        String reason = switch (error == null ? "" : error) {
            case "invalid_user_credentials" -> "Mot de passe incorrect";
            case "user_not_found" -> "Compte inconnu";
            case "user_disabled" -> "Compte désactivé";
            case "expired_code", "invalid_code" -> "Session de connexion expirée";
            case "cookie_not_found" -> "Cookies bloqués ou session expirée";
            default -> "Connexion refusée";
        };
        return AuditEvent.of(AuditAction.LOGIN_FAILED, AuditModule.SECURITY).result(AuditResult.FAILURE).description(reason + name);
    }
}
