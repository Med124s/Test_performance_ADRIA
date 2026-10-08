package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.dto.request.CreateUserRequest;
import com.loadpilot.backend.dto.response.PasswordPolicyResponse;
import com.loadpilot.backend.dto.response.UserCreatedResponse;
import com.loadpilot.backend.dto.response.UserSummaryResponse;
import com.loadpilot.backend.enums.AppRole;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.exception.BadRequestException;
import com.loadpilot.backend.exception.ConflictException;
import com.loadpilot.backend.exception.KeycloakAdminException;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.service.AuditLogService;
import com.loadpilot.backend.service.UserService;
import com.loadpilot.backend.service.keycloak.KeycloakAdminClient;
import com.loadpilot.backend.service.keycloak.KeycloakUserRecord;
import com.loadpilot.backend.service.audit.AuditEvent;
import com.loadpilot.backend.service.audit.AuditTargetType;
import com.loadpilot.backend.service.keycloak.NewKeycloakUser;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Keycloak (via KeycloakAdminClient) est l'unique source de verite pour les
 * utilisateurs et leurs roles (Phase 25) - aucun stockage local, aucun mot
 * de passe manipule ici. Seul le nombre d'executions lancees vient de la
 * base LoadPilot.
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);
    private static final List<String> UPDATE_PASSWORD = List.of("UPDATE_PASSWORD");

    private final KeycloakAdminClient keycloakAdminClient;
    private final AuditLogService auditLogService;
    private final ExecutionRepository executionRepository;

    /** Informations complementaires calculees une fois par appel (jamais par utilisateur). */
    private record Activity(Map<String, Instant> lastLogins, Map<String, Long> executionCounts) {
        Instant lastLogin(String userId) {
            return lastLogins.get(userId);
        }

        long executions(String userId) {
            return executionCounts.getOrDefault(userId, 0L);
        }
    }

    @Override
    public List<UserSummaryResponse> list() {
        Activity activity = loadActivity();
        return keycloakAdminClient.listUsers().stream()
                .map(user -> toSummary(user, activity))
                .toList();
    }

    @Override
    public UserSummaryResponse updateRole(String actorSubject, String userId, AppRole role) {
        AuditEvent audit = AuditEvent.of(AuditAction.ROLE_CHANGE, AuditModule.SECURITY);
        try {
            if (userId.equals(actorSubject) && role != AppRole.SUPER_ADMIN) {
                throw new ConflictException("Vous ne pouvez pas retirer votre propre rôle Administrateur.");
            }
            KeycloakUserRecord user = keycloakAdminClient.getUser(userId);
            audit.target(AuditTargetType.USER, userId, user.username());
            String before = resolveAppRoleName(keycloakAdminClient.getUserRealmRoleNames(userId));
            keycloakAdminClient.replaceUserAppRole(userId, role);
            auditLogService.record(audit.change("Rôle", AppRole.labelOf(before), role.label())
                    .description("Rôle modifié"));
            return toSummary(user, role.name(), loadActivity());
        } catch (RuntimeException e) {
            if (audit.targetId() == null) {
                audit.target(AuditTargetType.USER, userId, userId);
            }
            auditLogService.record(audit.description("Changement de rôle vers « " + role.label() + " » refusé").failed(e));
            throw e;
        }
    }

    @Override
    public UserSummaryResponse updateStatus(String actorSubject, String userId, boolean enabled) {
        AuditEvent audit = AuditEvent.of(enabled ? AuditAction.ENABLE : AuditAction.DISABLE, AuditModule.SECURITY);
        try {
            if (userId.equals(actorSubject) && !enabled) {
                throw new ConflictException("Vous ne pouvez pas désactiver votre propre compte.");
            }
            KeycloakUserRecord user = keycloakAdminClient.getUser(userId);
            audit.target(AuditTargetType.USER, userId, user.username());
            keycloakAdminClient.setUserEnabled(userId, enabled);
            auditLogService.record(audit.change("Statut", user.enabled() ? "Actif" : "Désactivé", enabled ? "Actif" : "Désactivé")
                    .description(enabled ? "Compte réactivé" : "Compte désactivé : connexion impossible"));
            return toSummary(user.withEnabled(enabled), loadActivity());
        } catch (RuntimeException e) {
            if (audit.targetId() == null) {
                audit.target(AuditTargetType.USER, userId, userId);
            }
            auditLogService.record(audit.failed(e));
            throw e;
        }
    }

    @Override
    public UserCreatedResponse create(CreateUserRequest request) {
        String username = request.username().trim().toLowerCase();
        String email = blankToNull(request.email());
        boolean withPassword = request.hasPassword();
        boolean temporary = withPassword && !Boolean.FALSE.equals(request.temporaryPassword());
        if (!withPassword && email == null) {
            throw new BadRequestException("L'e-mail est obligatoire pour envoyer le lien de choix du mot de passe (ou définissez le mot de passe maintenant).");
        }
        String userId;
        try {
            // Le mot de passe va directement a Keycloak (verifie selon la politique du realm), puis est oublie.
            userId = keycloakAdminClient.createUser(new NewKeycloakUser(username, email,
                    blankToNull(request.firstName()), blankToNull(request.lastName()),
                    withPassword ? request.password() : null, temporary));
        } catch (RuntimeException e) {
            auditLogService.record(AuditEvent.of(AuditAction.CREATE, AuditModule.USER)
                    .target(AuditTargetType.USER, null, username)
                    .description("Création du compte « " + username + " » refusée").failed(e));
            throw e;
        }
        try {
            keycloakAdminClient.replaceUserAppRole(userId, request.role());
        } catch (RuntimeException e) {
            // Jamais un compte laisse sans role applicatif : la creation est annulee.
            try {
                keycloakAdminClient.deleteUser(userId);
            } catch (RuntimeException cleanup) {
                log.warn("Compte {} cree mais role non attribue, suppression impossible : {}", username, cleanup.getMessage());
            }
            auditLogService.record(AuditEvent.of(AuditAction.CREATE, AuditModule.USER)
                    .target(AuditTargetType.USER, null, username)
                    .description("Création du compte annulée : le rôle n’a pas pu être attribué").failed(e));
            throw e;
        }
        boolean invitationSent = false;
        String credential;
        if (withPassword) {
            credential = temporary ? "Mot de passe initial défini par l’administrateur, à changer à la première connexion"
                    : "Mot de passe initial défini par l’administrateur";
        } else {
            try {
                keycloakAdminClient.sendActionsEmail(userId, UPDATE_PASSWORD);
                invitationSent = true;
                credential = "Lien de choix du mot de passe envoyé par e-mail";
            } catch (KeycloakAdminException e) {
                // Compte cree et utilisable : l'administrateur pourra renvoyer le lien plus tard.
                credential = "L’e-mail de choix du mot de passe n’a pas pu être envoyé";
                log.warn("Compte {} cree mais e-mail d'invitation non envoye : {}", username, e.getMessage());
            }
        }
        // Journal d'audit : jamais le mot de passe, seulement la facon dont il a ete fixe.
        auditLogService.record(AuditEvent.of(AuditAction.CREATE, AuditModule.USER)
                .target(AuditTargetType.USER, userId, username)
                .change("Rôle", null, request.role().label())
                .change("E-mail", null, email)
                .description(credential));
        KeycloakUserRecord created = keycloakAdminClient.getUser(userId);
        return new UserCreatedResponse(toSummary(created, request.role().name(), loadActivity()), invitationSent, withPassword);
    }

    @Override
    public void setPassword(String userId, String password, boolean temporary) {
        KeycloakUserRecord user = keycloakAdminClient.getUser(userId);
        AuditEvent audit = AuditEvent.of(AuditAction.PASSWORD_SET, AuditModule.SECURITY).target(AuditTargetType.USER, userId, user.username());
        try {
            keycloakAdminClient.setPassword(userId, password, temporary);
            // Jamais le mot de passe : seulement le fait qu'il a ete defini.
            auditLogService.record(audit.description(temporary
                    ? "Nouveau mot de passe défini par l’administrateur, à changer à la prochaine connexion"
                    : "Nouveau mot de passe défini par l’administrateur"));
        } catch (RuntimeException e) {
            auditLogService.record(audit.description("Nouveau mot de passe refusé").failed(e));
            throw e;
        }
    }

    @Override
    public PasswordPolicyResponse passwordPolicy() {
        return PasswordPolicyResponse.parse(keycloakAdminClient.getPasswordPolicy());
    }

    @Override
    public void sendPasswordReset(String userId) {
        KeycloakUserRecord user = keycloakAdminClient.getUser(userId);
        if (user.email() == null || user.email().isBlank()) {
            throw new BadRequestException("Cet utilisateur n'a pas d'adresse e-mail : impossible de lui envoyer le lien.");
        }
        if (!user.enabled()) {
            throw new BadRequestException("Ce compte est désactivé : réactivez-le avant d'envoyer un lien de réinitialisation.");
        }
        AuditEvent audit = AuditEvent.of(AuditAction.PASSWORD_RESET_LINK, AuditModule.SECURITY).target(AuditTargetType.USER, userId, user.username());
        try {
            keycloakAdminClient.sendActionsEmail(userId, UPDATE_PASSWORD);
            auditLogService.record(audit.description("Lien de réinitialisation envoyé à " + user.email()));
        } catch (RuntimeException e) {
            auditLogService.record(audit.description("Le lien de réinitialisation n’a pas pu être envoyé").failed(e));
            throw e;
        }
    }

    private Activity loadActivity() {
        Map<String, Instant> lastLogins;
        try {
            lastLogins = keycloakAdminClient.lastLoginByUser();
        } catch (KeycloakAdminException e) {
            // Evenements Keycloak desactives ou droit "view-events" absent : la
            // liste reste utilisable, simplement sans date de derniere connexion.
            log.warn("Dernieres connexions indisponibles : {}", e.getMessage());
            lastLogins = Map.of();
        }
        Map<String, Long> counts = new HashMap<>();
        executionRepository.countExecutionsBySubject().forEach(c -> counts.put(c.getSubject(), c.getTotal()));
        return new Activity(lastLogins == null ? Map.of() : lastLogins, counts);
    }

    private UserSummaryResponse toSummary(KeycloakUserRecord user, Activity activity) {
        Set<String> roleNames = keycloakAdminClient.getUserRealmRoleNames(user.id());
        return toSummary(user, resolveAppRoleName(roleNames), activity);
    }

    private UserSummaryResponse toSummary(KeycloakUserRecord user, String roleName, Activity activity) {
        return new UserSummaryResponse(user.id(), user.username(), user.email(), user.firstName(), user.lastName(),
                displayName(user), user.enabled(), roleName, user.createdAt(),
                activity.lastLogin(user.id()), activity.executions(user.id()));
    }

    /** "Prenom Nom" si renseignes dans Keycloak, sinon le username. */
    static String displayName(KeycloakUserRecord user) {
        String full = ((user.firstName() == null ? "" : user.firstName().trim()) + " "
                + (user.lastName() == null ? "" : user.lastName().trim())).trim();
        return full.isEmpty() ? user.username() : full;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Le seul des 3 AppRole reellement present dans les roles realm de cet
     * utilisateur - "null" si aucun (jamais un role invente par defaut).
     * Renvoie le nom de l'enum AppRole (ex: "SUPER_ADMIN"), pas le nom de
     * role Keycloak prefixe (ex: "ROLE_SUPER_ADMIN") - c'est ce que le
     * contrat BackendAppRole cote frontend attend. */
    private String resolveAppRoleName(Set<String> realmRoleNames) {
        for (AppRole role : AppRole.values()) {
            if (realmRoleNames.contains(role.toKeycloakRoleName())) {
                return role.name();
            }
        }
        return null;
    }
}
