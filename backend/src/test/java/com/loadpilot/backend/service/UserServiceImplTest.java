package com.loadpilot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.service.impl.UserServiceImpl;
import com.loadpilot.backend.service.keycloak.KeycloakAdminClient;
import com.loadpilot.backend.service.keycloak.KeycloakUserRecord;
import com.loadpilot.backend.service.keycloak.NewKeycloakUser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests (KeycloakAdminClient, AuditLogService et ExecutionRepository
 * mockes - aucun Keycloak reel) pour UserServiceImpl (Phase 25). Couvre en
 * particulier la regression detectee lors des tests manuels contre un
 * Keycloak reel : "role" doit toujours etre le nom brut de l'enum AppRole
 * (ex: "SUPER_ADMIN"), jamais le nom de role Keycloak prefixe (ex:
 * "ROLE_SUPER_ADMIN"), pour rester coherent avec le contrat BackendAppRole
 * cote frontend.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private KeycloakAdminClient keycloakAdminClient;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private ExecutionRepository executionRepository;

    @InjectMocks
    private UserServiceImpl userService;

    private static final String USER_ID = "756a64ce-0f11-4a18-b485-b2e04fe47b88";
    private static final String ADMIN_ID = "0b9f2c5e-1111-4a18-b485-b2e04fe47b88";

    private KeycloakUserRecord aUser() {
        return new KeycloakUserRecord(USER_ID, "viewer-test", "viewer-test@loadpilot.local", true, Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static ExecutionRepository.ExecutionCountBySubject count(String subject, long total) {
        return new ExecutionRepository.ExecutionCountBySubject() {
            @Override public String getSubject() { return subject; }
            @Override public long getTotal() { return total; }
        };
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
    void list_addsDisplayName_lastLogin_andExecutionCount() {
        Instant lastLogin = Instant.parse("2026-10-07T14:00:00Z");
        KeycloakUserRecord named = new KeycloakUserRecord(USER_ID, "viewer-test", "v@loadpilot.local", "Lina", "Viewer", true, Instant.parse("2026-01-01T00:00:00Z"));
        KeycloakUserRecord anonymous = new KeycloakUserRecord(ADMIN_ID, "admin-test", null, true, null);
        when(keycloakAdminClient.listUsers()).thenReturn(List.of(named, anonymous));
        when(keycloakAdminClient.getUserRealmRoleNames(anyString())).thenReturn(Set.of("ROLE_VIEWER"));
        when(keycloakAdminClient.lastLoginByUser()).thenReturn(Map.of(USER_ID, lastLogin));
        when(executionRepository.countExecutionsBySubject()).thenReturn(List.of(count(USER_ID, 7)));

        List<UserSummaryResponse> result = userService.list();

        assertThat(result.get(0).displayName()).isEqualTo("Lina Viewer");
        assertThat(result.get(0).lastLoginAt()).isEqualTo(lastLogin);
        assertThat(result.get(0).executionCount()).isEqualTo(7);
        // Sans prenom ni nom : le username ; sans connexion ni execution : null / 0.
        assertThat(result.get(1).displayName()).isEqualTo("admin-test");
        assertThat(result.get(1).lastLoginAt()).isNull();
        assertThat(result.get(1).executionCount()).isZero();
    }

    @Test
    void list_whenLoginEventsUnavailable_stillListsUsers_withoutLastLogin() {
        when(keycloakAdminClient.listUsers()).thenReturn(List.of(aUser()));
        when(keycloakAdminClient.getUserRealmRoleNames(USER_ID)).thenReturn(Set.of("ROLE_VIEWER"));
        when(keycloakAdminClient.lastLoginByUser()).thenThrow(new KeycloakAdminException("statut 403"));

        List<UserSummaryResponse> result = userService.list();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).lastLoginAt()).isNull();
    }

    @Test
    void updateRole_returnsPlainAppRoleName_notThePrefixedKeycloakName() {
        when(keycloakAdminClient.getUser(USER_ID)).thenReturn(aUser());

        UserSummaryResponse result = userService.updateRole(ADMIN_ID, USER_ID, AppRole.PERFORMANCE_ENGINEER);

        assertThat(result.role()).isEqualTo("PERFORMANCE_ENGINEER");
        verify(keycloakAdminClient).replaceUserAppRole(USER_ID, AppRole.PERFORMANCE_ENGINEER);
        verifyAudit(AuditAction.ROLE_CHANGE, AuditModule.SECURITY, AuditResult.SUCCESS);
    }

    @Test
    void updateRole_whenKeycloakRejects_recordsAuditFailure_andPropagatesException() {
        when(keycloakAdminClient.getUser(USER_ID)).thenReturn(aUser());
        doThrow(new ResourceNotFoundException("Role Keycloak introuvable"))
                .when(keycloakAdminClient).replaceUserAppRole(eq(USER_ID), any(AppRole.class));

        assertThatThrownBy(() -> userService.updateRole(ADMIN_ID, USER_ID, AppRole.SUPER_ADMIN))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyAudit(AuditAction.ROLE_CHANGE, AuditModule.SECURITY, AuditResult.FAILURE);
    }

    @Test
    void updateRole_adminCannotRemoveOwnAdminRole_butCanKeepIt() {
        assertThatThrownBy(() -> userService.updateRole(ADMIN_ID, ADMIN_ID, AppRole.VIEWER))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("propre rôle Administrateur");
        verify(keycloakAdminClient, never()).replaceUserAppRole(anyString(), any(AppRole.class));

        when(keycloakAdminClient.getUser(ADMIN_ID)).thenReturn(new KeycloakUserRecord(ADMIN_ID, "admin-test", null, true, null));
        assertThat(userService.updateRole(ADMIN_ID, ADMIN_ID, AppRole.SUPER_ADMIN).role()).isEqualTo("SUPER_ADMIN");
    }

    @Test
    void updateStatus_disablesRealKeycloakAccount_andReturnsUpdatedSummary() {
        when(keycloakAdminClient.getUser(USER_ID)).thenReturn(aUser());
        when(keycloakAdminClient.getUserRealmRoleNames(USER_ID)).thenReturn(Set.of("ROLE_VIEWER"));

        UserSummaryResponse result = userService.updateStatus(ADMIN_ID, USER_ID, false);

        assertThat(result.enabled()).isFalse();
        assertThat(result.role()).isEqualTo("VIEWER");
        verify(keycloakAdminClient).setUserEnabled(USER_ID, false);
        verifyAudit(AuditAction.DISABLE, AuditModule.SECURITY, AuditResult.SUCCESS);
    }

    @Test
    void updateStatus_adminCannotDisableOwnAccount() {
        assertThatThrownBy(() -> userService.updateStatus(ADMIN_ID, ADMIN_ID, false))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("propre compte");
        verify(keycloakAdminClient, never()).setUserEnabled(anyString(), eq(false));
        verifyAudit(AuditAction.DISABLE, AuditModule.SECURITY, AuditResult.FAILURE);
    }

    @Test
    void create_createsAccount_assignsRole_andSendsInvitation_withoutAnyPassword() {
        when(keycloakAdminClient.createUser(any(NewKeycloakUser.class))).thenReturn("new-id");
        when(keycloakAdminClient.getUser("new-id")).thenReturn(new KeycloakUserRecord("new-id", "lina.dupont", "lina@loadpilot.local", "Lina", "Dupont", true, null));

        UserCreatedResponse result = userService.create(new CreateUserRequest("Lina.Dupont", "lina@loadpilot.local", "Lina", " ", AppRole.PERFORMANCE_ENGINEER));

        verify(keycloakAdminClient).createUser(new NewKeycloakUser("lina.dupont", "lina@loadpilot.local", "Lina", null));
        verify(keycloakAdminClient).replaceUserAppRole("new-id", AppRole.PERFORMANCE_ENGINEER);
        verify(keycloakAdminClient).sendActionsEmail("new-id", List.of("UPDATE_PASSWORD"));
        assertThat(result.invitationSent()).isTrue();
        assertThat(result.user().role()).isEqualTo("PERFORMANCE_ENGINEER");
        assertThat(result.user().displayName()).isEqualTo("Lina Dupont");
        verifyAudit(AuditAction.CREATE, AuditModule.USER, AuditResult.SUCCESS);
    }

    @Test
    void create_whenRoleAssignmentFails_deletesTheAccount_neverLeavesAUserWithoutRole() {
        when(keycloakAdminClient.createUser(any(NewKeycloakUser.class))).thenReturn("new-id");
        doThrow(new ResourceNotFoundException("role introuvable")).when(keycloakAdminClient).replaceUserAppRole("new-id", AppRole.VIEWER);

        assertThatThrownBy(() -> userService.create(new CreateUserRequest("lina", "lina@loadpilot.local", null, null, AppRole.VIEWER)))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(keycloakAdminClient).deleteUser("new-id");
        verify(keycloakAdminClient, never()).sendActionsEmail(anyString(), any());
    }

    @Test
    void create_whenInvitationEmailFails_keepsAccount_andReportsIt() {
        when(keycloakAdminClient.createUser(any(NewKeycloakUser.class))).thenReturn("new-id");
        doThrow(new KeycloakAdminException("SMTP")).when(keycloakAdminClient).sendActionsEmail(eq("new-id"), any());
        when(keycloakAdminClient.getUser("new-id")).thenReturn(new KeycloakUserRecord("new-id", "lina", "lina@loadpilot.local", true, null));

        UserCreatedResponse result = userService.create(new CreateUserRequest("lina", "lina@loadpilot.local", null, null, AppRole.VIEWER));

        assertThat(result.invitationSent()).isFalse();
        verify(keycloakAdminClient, never()).deleteUser(anyString());
    }

    // ------------------------------------------------------------
    // Mot de passe defini par l'administrateur
    // ------------------------------------------------------------

    private static final String SECRET = "Tmp-Secret-42x";

    /** Toutes les descriptions passees au journal d'audit. */
    /** Toutes les entrees passees au journal d'audit. */
    private List<com.loadpilot.backend.service.audit.AuditEvent> auditEvents() {
        org.mockito.ArgumentCaptor<com.loadpilot.backend.service.audit.AuditEvent> captor =
                org.mockito.ArgumentCaptor.forClass(com.loadpilot.backend.service.audit.AuditEvent.class);
        verify(auditLogService, org.mockito.Mockito.atLeastOnce()).record(captor.capture());
        return captor.getAllValues();
    }

    /** Descriptions, objets et changements journalises (pour verifier qu'aucun mot de passe n'y figure). */
    private List<String> auditDescriptions() {
        return auditEvents().stream().map(e -> e.description()).toList();
    }

    private String auditText() {
        return auditEvents().stream().map(e -> e.description() + "|" + e.targetLabel() + "|" + e.changes()).reduce("", String::concat);
    }

    private void verifyAudit(AuditAction action, AuditModule module, AuditResult result) {
        assertThat(auditEvents()).anySatisfy(e -> {
            assertThat(e.action()).isEqualTo(action);
            assertThat(e.module()).isEqualTo(module);
            assertThat(e.result()).isEqualTo(result);
        });
    }

    @Test
    void create_withTemporaryPassword_setsItAtCreation_sendsNoEmail_andNeverAuditsIt() {
        when(keycloakAdminClient.createUser(any(NewKeycloakUser.class))).thenReturn("new-id");
        when(keycloakAdminClient.getUser("new-id")).thenReturn(new KeycloakUserRecord("new-id", "lina", null, true, null));

        UserCreatedResponse result = userService.create(new CreateUserRequest("lina", "", null, null, AppRole.VIEWER, SECRET, true));

        verify(keycloakAdminClient).createUser(new NewKeycloakUser("lina", null, null, null, SECRET, true));
        verify(keycloakAdminClient, never()).sendActionsEmail(anyString(), any());
        assertThat(result.passwordSet()).isTrue();
        assertThat(result.invitationSent()).isFalse();
        assertThat(result.toString()).doesNotContain(SECRET);
        assertThat(auditDescriptions()).singleElement().satisfies(d ->
                assertThat(d).isEqualTo("Mot de passe initial défini par l’administrateur, à changer à la première connexion"));
        assertThat(auditText()).doesNotContain(SECRET);
    }

    @Test
    void create_withPermanentPassword_whenTemporaryIsFalse() {
        when(keycloakAdminClient.createUser(any(NewKeycloakUser.class))).thenReturn("new-id");
        when(keycloakAdminClient.getUser("new-id")).thenReturn(new KeycloakUserRecord("new-id", "lina", "lina@loadpilot.local", true, null));

        userService.create(new CreateUserRequest("lina", "lina@loadpilot.local", null, null, AppRole.VIEWER, SECRET, false));

        verify(keycloakAdminClient).createUser(new NewKeycloakUser("lina", "lina@loadpilot.local", null, null, SECRET, false));
        assertThat(auditDescriptions()).singleElement().satisfies(d -> assertThat(d).isEqualTo("Mot de passe initial défini par l’administrateur"));
        assertThat(auditText()).doesNotContain(SECRET);
    }

    @Test
    void create_withoutPasswordNorEmail_isRefused_beforeAnyKeycloakCall() {
        assertThatThrownBy(() -> userService.create(new CreateUserRequest("lina", " ", null, null, AppRole.VIEWER, null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("L'e-mail est obligatoire");
        verify(keycloakAdminClient, never()).createUser(any(NewKeycloakUser.class));
    }

    @Test
    void create_passwordRefusedByPolicy_propagatesFrenchMessage_andNoAccountIsLeft() {
        when(keycloakAdminClient.createUser(any(NewKeycloakUser.class)))
                .thenThrow(new BadRequestException("Le mot de passe doit contenir au moins 8 caractères."));

        assertThatThrownBy(() -> userService.create(new CreateUserRequest("lina", null, null, null, AppRole.VIEWER, "Ab1", true)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Le mot de passe doit contenir au moins 8 caractères.");
        verify(keycloakAdminClient, never()).replaceUserAppRole(anyString(), any(AppRole.class));
        assertThat(auditText()).doesNotContain("Ab1");
    }

    @Test
    void setPassword_temporary_auditsOnlyThatAPasswordWasSet() {
        when(keycloakAdminClient.getUser(USER_ID)).thenReturn(aUser());

        userService.setPassword(USER_ID, SECRET, true);

        verify(keycloakAdminClient).setPassword(USER_ID, SECRET, true);
        assertThat(auditEvents()).singleElement().satisfies(e -> {
            assertThat(e.action()).isEqualTo(AuditAction.PASSWORD_SET);
            assertThat(e.module()).isEqualTo(AuditModule.SECURITY);
            assertThat(e.targetLabel()).isEqualTo("viewer-test");
            assertThat(e.description()).isEqualTo("Nouveau mot de passe défini par l’administrateur, à changer à la prochaine connexion");
        });
        assertThat(auditText()).doesNotContain(SECRET);
    }

    @Test
    void setPassword_refused_recordsFailure_withoutThePassword() {
        when(keycloakAdminClient.getUser(USER_ID)).thenReturn(aUser());
        doThrow(new BadRequestException("Le mot de passe doit contenir au moins 1 chiffre."))
                .when(keycloakAdminClient).setPassword(USER_ID, "Abcdefgh", false);

        assertThatThrownBy(() -> userService.setPassword(USER_ID, "Abcdefgh", false)).isInstanceOf(BadRequestException.class);
        verifyAudit(AuditAction.PASSWORD_SET, AuditModule.SECURITY, AuditResult.FAILURE);
        assertThat(auditText()).doesNotContain("Abcdefgh");
    }

    @Test
    void passwordPolicy_isReadFromTheRealm() {
        when(keycloakAdminClient.getPasswordPolicy())
                .thenReturn("length(10) and maxLength(64) and upperCase(1) and lowerCase(2) and digits(1) and specialChars(undefined) and notUsername(undefined)");

        PasswordPolicyResponse policy = userService.passwordPolicy();

        assertThat(policy).isEqualTo(new PasswordPolicyResponse(10, 64, 1, 2, 1, 1, true, false));
        when(keycloakAdminClient.getPasswordPolicy()).thenReturn("");
        assertThat(userService.passwordPolicy()).isEqualTo(new PasswordPolicyResponse(0, 0, 0, 0, 0, 0, false, false));
    }

    @Test
    void requestsAndNewUsers_neverPrintThePassword() {
        assertThat(new CreateUserRequest("lina", null, null, null, AppRole.VIEWER, SECRET, true).toString()).doesNotContain(SECRET).contains("password=***");
        assertThat(new com.loadpilot.backend.dto.request.SetPasswordRequest(SECRET, true).toString()).doesNotContain(SECRET);
        assertThat(new NewKeycloakUser("lina", null, null, null, SECRET, true).toString()).doesNotContain(SECRET);
    }

    @Test
    void sendPasswordReset_sendsKeycloakUpdatePasswordEmail() {
        when(keycloakAdminClient.getUser(USER_ID)).thenReturn(aUser());

        userService.sendPasswordReset(USER_ID);

        verify(keycloakAdminClient).sendActionsEmail(USER_ID, List.of("UPDATE_PASSWORD"));
        verifyAudit(AuditAction.PASSWORD_RESET_LINK, AuditModule.SECURITY, AuditResult.SUCCESS);
    }

    @Test
    void sendPasswordReset_refusesUserWithoutEmail_orDisabledAccount() {
        when(keycloakAdminClient.getUser(USER_ID)).thenReturn(new KeycloakUserRecord(USER_ID, "viewer-test", null, true, null));
        assertThatThrownBy(() -> userService.sendPasswordReset(USER_ID)).isInstanceOf(BadRequestException.class).hasMessageContaining("e-mail");

        when(keycloakAdminClient.getUser(USER_ID)).thenReturn(aUser().withEnabled(false));
        assertThatThrownBy(() -> userService.sendPasswordReset(USER_ID)).isInstanceOf(BadRequestException.class).hasMessageContaining("désactivé");

        verify(keycloakAdminClient, never()).sendActionsEmail(anyString(), any());
    }
}
