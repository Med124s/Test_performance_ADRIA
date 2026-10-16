package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.loadpilot.backend.entity.AuditLog;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.exception.ConflictException;
import com.loadpilot.backend.repository.AuditLogRepository;
import com.loadpilot.backend.service.keycloak.KeycloakAdminClient;
import com.loadpilot.backend.service.keycloak.KeycloakUserRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * « Mon compte » : identite (prenom, nom, e-mail) modifiable par le Super administrateur seulement ;
 * Ingenieur performance et Observateur en lecture seule (403 meme en appelant l'API directement),
 * preferences toujours permises, chaque modification et chaque refus journalises.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProfileIdentityTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AuditLogRepository auditLogRepository;
    @MockBean private JwtDecoder jwtDecoder;
    @MockBean private KeycloakAdminClient keycloak;

    private static final String ADMIN = "token-id-admin";
    private static final String ENGINEER = "token-id-engineer";
    private static final String VIEWER = "token-id-viewer";

    @BeforeEach
    void tokens() {
        auditLogRepository.deleteAll();
        when(jwtDecoder.decode(ADMIN)).thenReturn(jwt(ADMIN, "sub-id-admin", "admin-id", "Admin", "Test", "admin@loadpilot.local", "ROLE_SUPER_ADMIN"));
        when(jwtDecoder.decode(ENGINEER)).thenReturn(jwt(ENGINEER, "sub-id-eng", "engineer-id", "Engineer", "Test", "eng@loadpilot.local", "ROLE_PERFORMANCE_ENGINEER"));
        when(jwtDecoder.decode(VIEWER)).thenReturn(jwt(VIEWER, "sub-id-viewer", "viewer-id", "Viewer", "Test", "viewer@loadpilot.local", "ROLE_VIEWER"));
        when(keycloak.getUser("sub-id-admin")).thenReturn(
                new KeycloakUserRecord("sub-id-admin", "admin-id", "admin@loadpilot.local", "Admin", "Test", true, Instant.now()));
    }

    private static Jwt jwt(String token, String sub, String username, String first, String last, String email, String role) {
        return Jwt.withTokenValue(token).header("alg", "none").subject(sub)
                .claim("realm_access", Map.of("roles", List.of(role)))
                .claim("preferred_username", username).claim("name", first + " " + last)
                .claim("given_name", first).claim("family_name", last).claim("email", email)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token, String json) {
        return request.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private List<AuditLog> audits() {
        return auditLogRepository.findAll().stream().filter(a -> a.getAction() == AuditAction.UPDATE).toList();
    }

    @Test
    void getProfile_identityEditableForSuperAdminOnly_firstAndLastNameFromToken() throws Exception {
        mockMvc.perform(get("/api/profile").header("Authorization", "Bearer " + ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canEditIdentity").value(true))
                .andExpect(jsonPath("$.firstName").value("Admin"))
                .andExpect(jsonPath("$.lastName").value("Test"))
                .andExpect(jsonPath("$.appRoleLabel").value("Super administrateur"));
        for (String[] t : new String[][] {{ENGINEER, "Ingénieur performance"}, {VIEWER, "Observateur"}}) {
            mockMvc.perform(get("/api/profile").header("Authorization", "Bearer " + t[0]))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.canEditIdentity").value(false))
                    .andExpect(jsonPath("$.appRoleLabel").value(t[1]));
        }
    }

    @Test
    void engineerAndViewer_cannotChangeIdentity_403EvenDirectly_neverSentToKeycloak_refusalAudited() throws Exception {
        for (String token : List.of(ENGINEER, VIEWER)) {
            mockMvc.perform(as(put("/api/profile/identity"), token, "{\"firstName\":\"X\",\"lastName\":\"Y\",\"email\":\"x@y.z\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("Seul un Super administrateur peut modifier son nom et son e-mail."));
            // Corps invalide : toujours 403 (le rôle est vérifié avant les champs), jamais 400.
            mockMvc.perform(as(put("/api/profile/identity"), token, "{}")).andExpect(status().isForbidden());
            // Par la route des préférences aussi.
            mockMvc.perform(as(patch("/api/profile"), token, "{\"name\":\"Pirate\",\"email\":\"pirate@evil.com\"}"))
                    .andExpect(status().isForbidden());
        }
        verify(keycloak, never()).updateUserIdentity(anyString(), anyString(), anyString(), anyString());
        List<AuditLog> refused = audits();
        // Un même refus répété dans la même seconde n'est écrit qu'une fois (anti-doublon du journal).
        assertThat(refused).isNotEmpty().allSatisfy(a -> {
            assertThat(a.getResult()).isEqualTo(AuditResult.FAILURE);
            assertThat(a.getDescription()).startsWith("Modification de mon compte refusée").contains("Seul un Super administrateur peut modifier son nom et son e-mail.");
        });
        assertThat(refused).extracting(AuditLog::getUsername).contains("engineer-id", "viewer-id").containsOnly("engineer-id", "viewer-id");
    }

    @Test
    void everyone_keepsPersonalPreferences_timezoneAndTheme() throws Exception {
        for (String token : List.of(ENGINEER, VIEWER)) {
            mockMvc.perform(as(patch("/api/profile"), token, "{\"timezone\":\"Europe/Paris\",\"theme\":\"DARK\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.timezone").value("Europe/Paris"))
                    .andExpect(jsonPath("$.theme").value("DARK"));
        }
    }

    @Test
    void nobody_changesOwnUsernameRoleOrStatusFromProfile_evenSuperAdmin() throws Exception {
        String expected = "Votre nom d’utilisateur, votre rôle et l’état de votre compte ne sont pas modifiables depuis votre profil.";
        for (String json : List.of("{\"role\":\"VIEWER\"}", "{\"enabled\":false}", "{\"username\":\"root\"}", "{\"appRole\":\"SUPER_ADMIN\"}")) {
            mockMvc.perform(as(patch("/api/profile"), ADMIN, json)).andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value(expected));
            mockMvc.perform(as(patch("/api/profile"), VIEWER, json)).andExpect(status().isForbidden());
        }
        mockMvc.perform(as(put("/api/profile/identity"), ADMIN, "{\"firstName\":\"A\",\"lastName\":\"B\",\"email\":\"a@b.fr\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value(expected));
        verify(keycloak, never()).updateUserIdentity(anyString(), anyString(), anyString(), anyString());
        verify(keycloak, never()).replaceUserAppRole(anyString(), any());
        verify(keycloak, never()).setUserEnabled(anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void superAdmin_changesNameAndEmail_inKeycloak_auditedBeforeAfter() throws Exception {
        mockMvc.perform(as(put("/api/profile/identity"), ADMIN, "{\"firstName\":\"  Amina \",\"lastName\":\"Alaoui\",\"email\":\"amina@loadpilot.local\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Amina Alaoui"))
                .andExpect(jsonPath("$.firstName").value("Amina"))
                .andExpect(jsonPath("$.email").value("amina@loadpilot.local"))
                .andExpect(jsonPath("$.username").value("admin-id"))
                .andExpect(jsonPath("$.appRoleLabel").value("Super administrateur"));
        verify(keycloak).updateUserIdentity("sub-id-admin", "Amina", "Alaoui", "amina@loadpilot.local");

        List<AuditLog> entries = audits();
        assertThat(entries).hasSize(1);
        AuditLog entry = entries.get(0);
        assertThat(entry.getResult()).isEqualTo(AuditResult.SUCCESS);
        assertThat(entry.getUsername()).isEqualTo("admin-id");
        assertThat(entry.getTargetLabel()).isEqualTo("admin-id");
        assertThat(entry.getDescription()).isEqualTo("Modification de mon compte (nom et e-mail)");
        assertThat(entry.getChanges())
                .contains("\"Prénom\"").contains("\"Admin\"").contains("\"Amina\"")
                .contains("\"Nom\"").contains("\"Test\"").contains("\"Alaoui\"")
                .contains("\"E-mail\"").contains("admin@loadpilot.local").contains("amina@loadpilot.local");
    }

    @Test
    void superAdmin_unchangedValues_nothingSentNothingAudited_invalidFields400() throws Exception {
        mockMvc.perform(as(put("/api/profile/identity"), ADMIN, "{\"firstName\":\"Admin\",\"lastName\":\"Test\",\"email\":\"ADMIN@loadpilot.local\"}"))
                .andExpect(status().isOk());
        verify(keycloak, never()).updateUserIdentity(anyString(), anyString(), anyString(), anyString());
        assertThat(audits()).isEmpty();

        mockMvc.perform(as(put("/api/profile/identity"), ADMIN, "{\"firstName\":\"\",\"lastName\":\"Test\",\"email\":\"pas-un-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Le prénom est obligatoire. L’e-mail n’est pas valide."));
    }

    @Test
    void superAdmin_emailAlreadyUsed_409_failureAudited() throws Exception {
        doThrow(new ConflictException("Cet e-mail est déjà utilisé par un autre compte."))
                .when(keycloak).updateUserIdentity(anyString(), anyString(), anyString(), anyString());
        mockMvc.perform(as(put("/api/profile/identity"), ADMIN, "{\"firstName\":\"Admin\",\"lastName\":\"Test\",\"email\":\"engineer@loadpilot.local\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Cet e-mail est déjà utilisé par un autre compte."));
        assertThat(audits()).singleElement().satisfies(a -> assertThat(a.getResult()).isEqualTo(AuditResult.FAILURE));
    }
}
