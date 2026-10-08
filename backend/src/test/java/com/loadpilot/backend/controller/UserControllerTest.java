package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.enums.AppRole;
import com.loadpilot.backend.exception.ConflictException;
import com.loadpilot.backend.exception.KeycloakAdminException;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.service.keycloak.KeycloakAdminClient;
import com.loadpilot.backend.service.keycloak.KeycloakUserRecord;
import com.loadpilot.backend.service.keycloak.NewKeycloakUser;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Verifie /api/users (Phase 25) : reserve a SUPER_ADMIN uniquement (voir
 * UserController), jamais VIEWER ni PERFORMANCE_ENGINEER (contrairement a
 * l'audit) - l'administration des comptes/roles est l'operation la plus
 * sensible du systeme. KeycloakAdminClient est mocke : aucun Keycloak reel
 * n'est necessaire pour ce test (verifie separement par curl contre un
 * Keycloak local reel, voir rapport Phase 25).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.junit.jupiter.api.extension.ExtendWith(OutputCaptureExtension.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtDecoder jwtDecoder;

    @MockBean
    private KeycloakAdminClient keycloakAdminClient;

    private static final RequestPostProcessor AS_SUPER_ADMIN =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
    private static final RequestPostProcessor AS_ENGINEER =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_PERFORMANCE_ENGINEER"));
    private static final RequestPostProcessor AS_VIEWER =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"));

    private static final String USER_ID = "756a64ce-0f11-4a18-b485-b2e04fe47b88";

    private KeycloakUserRecord aUser() {
        return new KeycloakUserRecord(USER_ID, "viewer-test", "viewer-test@loadpilot.local", true, Instant.parse("2026-01-01T00:00:00Z"));
    }

    @BeforeEach
    void setUp() {
        when(keycloakAdminClient.getUserRealmRoleNames(anyString())).thenReturn(Set.of("ROLE_VIEWER"));
    }

    // ------------------------------------------------------------
    // Securite - GET /api/users
    // ------------------------------------------------------------

    @Test
    void list_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
    }

    @Test
    void list_asViewer_returns403() throws Exception {
        mockMvc.perform(get("/api/users").with(AS_VIEWER)).andExpect(status().isForbidden());
    }

    @Test
    void list_asEngineer_returns403() throws Exception {
        // Contrairement a l'audit : aucune justification metier a donner a
        // PERFORMANCE_ENGINEER un acces a l'administration des comptes.
        mockMvc.perform(get("/api/users").with(AS_ENGINEER)).andExpect(status().isForbidden());
    }

    @Test
    void list_asSuperAdmin_returns200_andRealFieldsOnly() throws Exception {
        when(keycloakAdminClient.listUsers()).thenReturn(List.of(aUser()));

        mockMvc.perform(get("/api/users").with(AS_SUPER_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(USER_ID))
                .andExpect(jsonPath("$[0].username").value("viewer-test"))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].role").value("VIEWER"));
    }

    // ------------------------------------------------------------
    // Securite - PUT /api/users/{id}/role et /status
    // ------------------------------------------------------------

    @Test
    void updateRole_withoutToken_returns401() throws Exception {
        mockMvc.perform(put("/api/users/" + USER_ID + "/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"SUPER_ADMIN\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateRole_asViewer_returns403() throws Exception {
        mockMvc.perform(put("/api/users/" + USER_ID + "/role").with(AS_VIEWER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"SUPER_ADMIN\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateRole_asEngineer_returns403() throws Exception {
        mockMvc.perform(put("/api/users/" + USER_ID + "/role").with(AS_ENGINEER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"SUPER_ADMIN\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateRole_asSuperAdmin_callsRealKeycloakRoleChange_returnsPlainRoleName() throws Exception {
        when(keycloakAdminClient.getUser(USER_ID)).thenReturn(aUser());

        mockMvc.perform(put("/api/users/" + USER_ID + "/role").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"PERFORMANCE_ENGINEER\"}"))
                .andExpect(status().isOk())
                // Le contrat BackendAppRole cote frontend attend le nom brut de
                // l'enum ("PERFORMANCE_ENGINEER"), jamais le nom Keycloak prefixe
                // ("ROLE_PERFORMANCE_ENGINEER") - garde-fou pour la regression
                // detectee et corrigee en Phase 25 (voir UserServiceImpl).
                .andExpect(jsonPath("$.role").value("PERFORMANCE_ENGINEER"));

        verify(keycloakAdminClient).replaceUserAppRole(USER_ID, AppRole.PERFORMANCE_ENGINEER);
    }

    @Test
    void updateRole_invalidRoleValue_returns400() throws Exception {
        mockMvc.perform(put("/api/users/" + USER_ID + "/role").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"NOT_A_REAL_ROLE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateRole_unknownUser_returns404() throws Exception {
        when(keycloakAdminClient.getUser(anyString()))
                .thenThrow(new ResourceNotFoundException("Utilisateur introuvable : nope"));

        mockMvc.perform(put("/api/users/nope/role").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateStatus_asSuperAdmin_callsRealKeycloakStatusChange() throws Exception {
        when(keycloakAdminClient.getUser(USER_ID)).thenReturn(aUser());

        mockMvc.perform(put("/api/users/" + USER_ID + "/status").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        verify(keycloakAdminClient).setUserEnabled(USER_ID, false);
    }

    @Test
    void updateStatus_asViewer_returns403() throws Exception {
        mockMvc.perform(put("/api/users/" + USER_ID + "/status").with(AS_VIEWER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateStatus_missingBody_returns400() throws Exception {
        mockMvc.perform(put("/api/users/" + USER_ID + "/status").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------
    // Protection de l'administrateur connecte (sujet du Jwt = id cible)
    // ------------------------------------------------------------

    private static final RequestPostProcessor AS_SUPER_ADMIN_HIMSELF =
            jwt().jwt(j -> j.subject(USER_ID)).authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));

    @Test
    void updateRole_ownAdminRoleRemoval_returns409_andKeycloakUntouched() throws Exception {
        mockMvc.perform(put("/api/users/" + USER_ID + "/role").with(AS_SUPER_ADMIN_HIMSELF)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Vous ne pouvez pas retirer votre propre rôle Administrateur."));
        verify(keycloakAdminClient, never()).replaceUserAppRole(anyString(), any(AppRole.class));
    }

    @Test
    void updateStatus_disablingOwnAccount_returns409_andKeycloakUntouched() throws Exception {
        mockMvc.perform(put("/api/users/" + USER_ID + "/status").with(AS_SUPER_ADMIN_HIMSELF)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isConflict());
        verify(keycloakAdminClient, never()).setUserEnabled(anyString(), anyBoolean());
    }

    // ------------------------------------------------------------
    // POST /api/users et /{id}/reset-password
    // ------------------------------------------------------------

    private static final String NEW_USER = "{\"username\":\"lina.dupont\",\"email\":\"lina@loadpilot.local\",\"firstName\":\"Lina\",\"lastName\":\"Dupont\",\"role\":\"PERFORMANCE_ENGINEER\"}";

    @Test
    void create_asSuperAdmin_returns201_withInvitationSent() throws Exception {
        when(keycloakAdminClient.createUser(any(NewKeycloakUser.class))).thenReturn("new-id");
        when(keycloakAdminClient.getUser("new-id")).thenReturn(
                new KeycloakUserRecord("new-id", "lina.dupont", "lina@loadpilot.local", "Lina", "Dupont", true, null));

        mockMvc.perform(post("/api/users").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content(NEW_USER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.invitationSent").value(true))
                .andExpect(jsonPath("$.user.displayName").value("Lina Dupont"))
                .andExpect(jsonPath("$.user.role").value("PERFORMANCE_ENGINEER"));
        verify(keycloakAdminClient).sendActionsEmail("new-id", List.of("UPDATE_PASSWORD"));
    }

    @Test
    void create_existingUsername_returns409() throws Exception {
        when(keycloakAdminClient.createUser(any(NewKeycloakUser.class)))
                .thenThrow(new ConflictException("Un utilisateur avec ce nom d'utilisateur ou cet e-mail existe déjà."));

        mockMvc.perform(post("/api/users").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content(NEW_USER))
                .andExpect(status().isConflict());
    }

    @Test
    void create_invalidBody_returns400() throws Exception {
        mockMvc.perform(post("/api/users").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"a b\",\"email\":\"pas-un-email\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isBadRequest());
        verify(keycloakAdminClient, never()).createUser(any(NewKeycloakUser.class));
    }

    @Test
    void create_andResetPassword_asEngineer_return403() throws Exception {
        mockMvc.perform(post("/api/users").with(AS_ENGINEER).contentType(MediaType.APPLICATION_JSON).content(NEW_USER))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/users/" + USER_ID + "/reset-password").with(AS_ENGINEER))
                .andExpect(status().isForbidden());
    }

    @Test
    void resetPassword_asSuperAdmin_returns204_andSendsKeycloakEmail() throws Exception {
        when(keycloakAdminClient.getUser(USER_ID)).thenReturn(aUser());

        mockMvc.perform(post("/api/users/" + USER_ID + "/reset-password").with(AS_SUPER_ADMIN))
                .andExpect(status().isNoContent());
        verify(keycloakAdminClient).sendActionsEmail(USER_ID, List.of("UPDATE_PASSWORD"));
    }

    // ------------------------------------------------------------
    // Mot de passe defini par l'administrateur : jamais renvoye, journalise ni audite
    // ------------------------------------------------------------

    private static final String SECRET = "Zq7-Secret-Value-9";

    @Autowired
    private com.loadpilot.backend.repository.AuditLogRepository auditLogRepository;

    private void assertSecretNowhere(String responseBody, CapturedOutput output) {
        assertThat(responseBody).doesNotContain(SECRET);
        assertThat(output.getAll()).doesNotContain(SECRET);
        assertThat(auditLogRepository.findAll()).allSatisfy(a -> assertThat(a.getDescription() + "|" + a.getTargetLabel() + "|" + a.getChanges()).doesNotContain(SECRET));
    }

    @Test
    void create_withTemporaryPassword_returns201_withoutThePassword_inResponseLogsOrAudit(CapturedOutput output) throws Exception {
        when(keycloakAdminClient.createUser(any(NewKeycloakUser.class))).thenReturn("new-id");
        when(keycloakAdminClient.getUser("new-id")).thenReturn(new KeycloakUserRecord("new-id", "lina.dupont", null, true, null));

        String body = mockMvc.perform(post("/api/users").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"lina.dupont\",\"role\":\"VIEWER\",\"password\":\"" + SECRET + "\",\"temporaryPassword\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passwordSet").value(true))
                .andExpect(jsonPath("$.invitationSent").value(false))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        verify(keycloakAdminClient).createUser(new NewKeycloakUser("lina.dupont", null, null, null, SECRET, true));
        verify(keycloakAdminClient, never()).sendActionsEmail(anyString(), any());
        assertThat(auditLogRepository.findAll()).anySatisfy(a -> assertThat(a.getDescription()).isEqualTo("Mot de passe initial défini par l’administrateur, à changer à la première connexion"));
        assertSecretNowhere(body, output);
    }

    @Test
    void create_withoutPasswordNorEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/users").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"lina\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isBadRequest());
        verify(keycloakAdminClient, never()).createUser(any(NewKeycloakUser.class));
    }

    @Test
    void setPassword_asSuperAdmin_returns204_withoutThePasswordAnywhere(CapturedOutput output) throws Exception {
        when(keycloakAdminClient.getUser(USER_ID)).thenReturn(aUser());

        String body = mockMvc.perform(put("/api/users/" + USER_ID + "/password").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + SECRET + "\",\"temporary\":false}"))
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getContentAsString();

        verify(keycloakAdminClient).setPassword(USER_ID, SECRET, false);
        assertThat(auditLogRepository.findAll()).anySatisfy(a -> {
            assertThat(a.getAction()).isEqualTo(com.loadpilot.backend.enums.AuditAction.PASSWORD_SET);
            assertThat(a.getModule()).isEqualTo(com.loadpilot.backend.enums.AuditModule.SECURITY);
            assertThat(a.getTargetLabel()).isEqualTo("viewer-test");
            assertThat(a.getDescription()).isEqualTo("Nouveau mot de passe défini par l’administrateur");
        });
        assertSecretNowhere(body, output);
    }

    @Test
    void setPassword_refusedByPolicy_returns400_withFrenchMessage_andNoPassword(CapturedOutput output) throws Exception {
        when(keycloakAdminClient.getUser(USER_ID)).thenReturn(aUser());
        org.mockito.Mockito.doThrow(new com.loadpilot.backend.exception.BadRequestException("Le mot de passe doit contenir au moins 1 chiffre."))
                .when(keycloakAdminClient).setPassword(USER_ID, SECRET, true);

        String body = mockMvc.perform(put("/api/users/" + USER_ID + "/password").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + SECRET + "\",\"temporary\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Le mot de passe doit contenir au moins 1 chiffre."))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertSecretNowhere(body, output);
    }

    @Test
    void setPassword_blankPassword_returns400() throws Exception {
        mockMvc.perform(put("/api/users/" + USER_ID + "/password").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"\",\"temporary\":true}"))
                .andExpect(status().isBadRequest());
        verify(keycloakAdminClient, never()).setPassword(anyString(), anyString(), anyBoolean());
    }

    @Test
    void passwordEndpoints_reservedToSuperAdmin() throws Exception {
        mockMvc.perform(put("/api/users/" + USER_ID + "/password").with(AS_ENGINEER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + SECRET + "\",\"temporary\":true}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/users/password-policy").with(AS_VIEWER)).andExpect(status().isForbidden());
        verify(keycloakAdminClient, never()).setPassword(anyString(), anyString(), anyBoolean());
    }

    @Test
    void passwordPolicy_asSuperAdmin_returnsTheRealmRules() throws Exception {
        when(keycloakAdminClient.getPasswordPolicy()).thenReturn("length(8) and maxLength(64) and upperCase(1) and lowerCase(1) and digits(1) and notUsername(undefined) and notEmail(undefined)");

        mockMvc.perform(get("/api/users/password-policy").with(AS_SUPER_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minLength").value(8))
                .andExpect(jsonPath("$.maxLength").value(64))
                .andExpect(jsonPath("$.minDigits").value(1))
                .andExpect(jsonPath("$.minSpecialChars").value(0))
                .andExpect(jsonPath("$.notUsername").value(true));
    }

    @Test
    void list_asSuperAdmin_whenKeycloakAdminUnreachable_returns502() throws Exception {
        when(keycloakAdminClient.listUsers()).thenThrow(new KeycloakAdminException("Administration Keycloak injoignable."));

        mockMvc.perform(get("/api/users").with(AS_SUPER_ADMIN))
                .andExpect(status().isBadGateway());
    }
}
