package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.dto.response.UserSummaryResponse;
import com.loadpilot.backend.enums.AppRole;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.service.AuditLogService;
import com.loadpilot.backend.service.UserService;
import com.loadpilot.backend.service.keycloak.KeycloakAdminClient;
import com.loadpilot.backend.service.keycloak.KeycloakUserRecord;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Keycloak (via KeycloakAdminClient) est l'unique source de verite pour les
 * utilisateurs et leurs roles (Phase 25) - aucun stockage local, aucun mot
 * de passe manipule ici.
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final KeycloakAdminClient keycloakAdminClient;
    private final AuditLogService auditLogService;

    @Override
    public List<UserSummaryResponse> list() {
        return keycloakAdminClient.listUsers().stream()
                .map(this::toSummary)
                .toList();
    }

    @Override
    public UserSummaryResponse updateRole(String userId, AppRole role) {
        try {
            KeycloakUserRecord user = keycloakAdminClient.getUser(userId);
            keycloakAdminClient.replaceUserAppRole(userId, role);
            auditLogService.record(AuditAction.UPDATE, AuditModule.USER, AuditResult.SUCCESS,
                    "Role updated for user " + user.username() + ": " + role.toKeycloakRoleName());
            return toSummary(new KeycloakUserRecord(user.id(), user.username(), user.email(), user.enabled(), user.createdAt()), role.name());
        } catch (RuntimeException e) {
            auditLogService.record(AuditAction.UPDATE, AuditModule.USER, AuditResult.FAILURE,
                    "Role update failed for user " + userId + ": " + e.getClass().getSimpleName());
            throw e;
        }
    }

    @Override
    public UserSummaryResponse updateStatus(String userId, boolean enabled) {
        try {
            KeycloakUserRecord user = keycloakAdminClient.getUser(userId);
            keycloakAdminClient.setUserEnabled(userId, enabled);
            auditLogService.record(AuditAction.UPDATE, AuditModule.USER, AuditResult.SUCCESS,
                    "User " + user.username() + (enabled ? " enabled" : " disabled"));
            return toSummary(new KeycloakUserRecord(user.id(), user.username(), user.email(), enabled, user.createdAt()));
        } catch (RuntimeException e) {
            auditLogService.record(AuditAction.UPDATE, AuditModule.USER, AuditResult.FAILURE,
                    "User status update failed for user " + userId + ": " + e.getClass().getSimpleName());
            throw e;
        }
    }

    private UserSummaryResponse toSummary(KeycloakUserRecord user) {
        Set<String> roleNames = keycloakAdminClient.getUserRealmRoleNames(user.id());
        return toSummary(user, resolveAppRoleName(roleNames));
    }

    private UserSummaryResponse toSummary(KeycloakUserRecord user, String roleName) {
        return new UserSummaryResponse(user.id(), user.username(), user.email(), user.enabled(), roleName, user.createdAt());
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
