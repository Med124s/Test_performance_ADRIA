package com.loadpilot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loadpilot.backend.dto.response.UserSummaryResponse;
import com.loadpilot.backend.enums.AppRole;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.service.impl.UserServiceImpl;
import com.loadpilot.backend.service.keycloak.KeycloakAdminClient;
import com.loadpilot.backend.service.keycloak.KeycloakUserRecord;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests (KeycloakAdminClient et AuditLogService mockes - aucun Keycloak
 * reel) pour UserServiceImpl (Phase 25). Couvre en particulier la
 * regression detectee lors des tests manuels contre un Keycloak reel :
 * "role" doit toujours etre le nom brut de l'enum AppRole (ex:
 * "SUPER_ADMIN"), jamais le nom de role Keycloak prefixe (ex:
 * "ROLE_SUPER_ADMIN"), pour rester coherent avec le contrat BackendAppRole
 * cote frontend.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private KeycloakAdminClient keycloakAdminClient;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private UserServiceImpl userService;

    private static final String USER_ID = "756a64ce-0f11-4a18-b485-b2e04fe47b88";

    private KeycloakUserRecord aUser() {
        return new KeycloakUserRecord(USER_ID, "viewer-test", "viewer-test@loadpilot.local", true, Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void list_mapsRealmRoleName_toPlainAppRoleName_neverThePrefixedKeycloakName() {
        when(keycloakAdminClient.listUsers()).thenReturn(List.of(aUser()));
        when(keycloakAdminClient.getUserRealmRoleNames(USER_ID)).thenReturn(Set.of("ROLE_SUPER_ADMIN"));

        List<UserSummaryResponse> result = userService.list();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).role()).isEqualTo("SUPER_ADMIN");
    }

    @Test
    void list_userWithNoAppRole_returnsNullRole_neverAnInventedDefault() {
        when(keycloakAdminClient.listUsers()).thenReturn(List.of(aUser()));
        when(keycloakAdminClient.getUserRealmRoleNames(USER_ID)).thenReturn(Set.of("offline_access", "uma_authorization"));

        List<UserSummaryResponse> result = userService.list();

        assertThat(result.get(0).role()).isNull();
    }

    @Test
    void updateRole_returnsPlainAppRoleName_notThePrefixedKeycloakName() {
        when(keycloakAdminClient.getUser(USER_ID)).thenReturn(aUser());

        UserSummaryResponse result = userService.updateRole(USER_ID, AppRole.PERFORMANCE_ENGINEER);

        assertThat(result.role()).isEqualTo("PERFORMANCE_ENGINEER");
        verify(keycloakAdminClient).replaceUserAppRole(USER_ID, AppRole.PERFORMANCE_ENGINEER);
        verify(auditLogService).record(eq(AuditAction.UPDATE), eq(AuditModule.USER), eq(AuditResult.SUCCESS), anyString());
    }

    @Test
    void updateRole_whenKeycloakRejects_recordsAuditFailure_andPropagatesException() {
        when(keycloakAdminClient.getUser(USER_ID)).thenReturn(aUser());
        org.mockito.Mockito.doThrow(new ResourceNotFoundException("Role Keycloak introuvable"))
                .when(keycloakAdminClient).replaceUserAppRole(eq(USER_ID), any(AppRole.class));

        assertThatThrownBy(() -> userService.updateRole(USER_ID, AppRole.SUPER_ADMIN))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(auditLogService).record(eq(AuditAction.UPDATE), eq(AuditModule.USER), eq(AuditResult.FAILURE), anyString());
    }

    @Test
    void updateStatus_disablesRealKeycloakAccount_andReturnsUpdatedSummary() {
        when(keycloakAdminClient.getUser(USER_ID)).thenReturn(aUser());
        when(keycloakAdminClient.getUserRealmRoleNames(USER_ID)).thenReturn(Set.of("ROLE_VIEWER"));

        UserSummaryResponse result = userService.updateStatus(USER_ID, false);

        assertThat(result.enabled()).isFalse();
        assertThat(result.role()).isEqualTo("VIEWER");
        verify(keycloakAdminClient).setUserEnabled(USER_ID, false);
        verify(auditLogService).record(eq(AuditAction.UPDATE), eq(AuditModule.USER), eq(AuditResult.SUCCESS), anyString());
    }
}
