package com.loadpilot.backend.service;

import com.loadpilot.backend.dto.response.ProfileSecurityResponse;
import com.loadpilot.backend.dto.response.ProfileSecurityResponse.Session;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.exception.KeycloakAdminException;
import com.loadpilot.backend.repository.AuditLogRepository;
import com.loadpilot.backend.service.audit.AuditEvent;
import com.loadpilot.backend.service.audit.AuditLabels;
import com.loadpilot.backend.service.audit.AuditTargetType;
import com.loadpilot.backend.service.audit.SecurityEventImporter;
import com.loadpilot.backend.service.keycloak.KeycloakAdminClient;
import com.loadpilot.backend.service.keycloak.KeycloakLogin;
import com.loadpilot.backend.service.keycloak.KeycloakSession;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/**
 * Securite du compte connecte (self-service, jamais le compte d'un autre) : derniere connexion,
 * sessions ouvertes, deconnexion de tous les appareils, journalisation du changement de mot de
 * passe fait sur la page du systeme d'authentification (jamais le mot de passe lui-meme).
 */
@Service
@RequiredArgsConstructor
public class ProfileSecurityService {

    private static final Logger log = LoggerFactory.getLogger(ProfileSecurityService.class);

    private final KeycloakAdminClient keycloak;
    private final AuditLogService auditLogService;
    private final AuditLogRepository auditLogRepository;
    private final SecurityEventImporter securityEventImporter;

    public ProfileSecurityResponse security(Jwt jwt) {
        String userId = jwt.getSubject();
        String currentSessionId = jwt.getClaimAsString("sid");
        Instant lastLoginAt = null;
        String lastLoginIp = null;
        String message = null;
        try {
            List<KeycloakLogin> logins = keycloak.recentLogins(userId, 1);
            if (!logins.isEmpty()) {
                lastLoginAt = logins.get(0).time();
                lastLoginIp = AuditLabels.ip(logins.get(0).ipAddress());
            }
        } catch (KeycloakAdminException e) {
            message = "Dernière connexion indisponible pour le moment.";
        }

        List<KeycloakSession> sessions;
        boolean devicesKnown = true;
        try {
            // Avec le jeton de l'utilisateur : navigateur, systeme et session courante.
            sessions = keycloak.accountSessions(jwt.getTokenValue());
        } catch (KeycloakAdminException e) {
            devicesKnown = false;
            try {
                sessions = keycloak.userSessions(userId);
            } catch (KeycloakAdminException second) {
                return new ProfileSecurityResponse(lastLoginAt, lastLoginIp, List.of(), false,
                        "Sessions indisponibles : le service d’authentification ne répond pas.");
            }
        }
        List<Session> out = sessions.stream()
                .map(s -> new Session(s.id(), deviceLabel(s), s.mobile(), AuditLabels.ip(s.ipAddress()), s.started(), s.lastAccess(),
                        s.current() || (s.id() != null && s.id().equals(currentSessionId))))
                .sorted(Comparator.comparing(Session::current).reversed()
                        .thenComparing(Session::lastAccess, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        return new ProfileSecurityResponse(lastLoginAt, lastLoginIp, out, devicesKnown, message);
    }

    /** « Chrome sur Windows », « Firefox », « Android »... ; null si rien n'est connu. */
    static String deviceLabel(KeycloakSession s) {
        String browser = s.browser() == null ? null : s.browser().replaceAll("/[\\d.]+$", "").trim();
        String os = s.os();
        if (browser != null && !browser.isBlank() && os != null) return browser + " sur " + os;
        if (browser != null && !browser.isBlank()) return browser;
        return os;
    }

    /** Ferme toutes les sessions de l'utilisateur (tous les appareils, y compris celui-ci) ; journalise. */
    public void logoutEverywhere(Jwt jwt) {
        String userId = jwt.getSubject();
        String username = jwt.getClaimAsString("preferred_username");
        try {
            keycloak.logoutUser(userId);
            auditLogService.record(AuditEvent.of(AuditAction.LOGOUT, AuditModule.SECURITY)
                    .target(AuditTargetType.USER, userId, username != null ? username : userId)
                    .description("Déconnexion de tous les appareils, demandée depuis Paramètres"));
        } catch (RuntimeException e) {
            auditLogService.record(AuditEvent.of(AuditAction.LOGOUT, AuditModule.SECURITY)
                    .target(AuditTargetType.USER, userId, username != null ? username : userId)
                    .description("Déconnexion de tous les appareils impossible").failed(e));
            throw e;
        }
    }

    /**
     * Retour de la page « changer mon mot de passe » : le changement est un evenement du systeme
     * d'authentification, importe ici sans attendre le prochain passage automatique (jamais une
     * entree fabriquee). Renvoie vrai si l'entree « Mot de passe changé » est au journal.
     */
    public boolean recordPasswordChange(Jwt jwt) {
        try {
            securityEventImporter.importNewEvents();
        } catch (RuntimeException e) {
            log.warn("Import des evenements de securite impossible : {}", e.getClass().getSimpleName());
        }
        return auditLogRepository.existsByActionAndUserIdAndDateAfter(AuditAction.PASSWORD_CHANGED, jwt.getSubject(),
                Instant.now().minus(Duration.ofMinutes(15)));
    }
}
