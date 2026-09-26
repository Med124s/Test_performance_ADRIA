package com.loadpilot.backend.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.enums.AppRole;
import com.loadpilot.backend.exception.KeycloakAdminException;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.service.keycloak.KeycloakAdminClient;
import com.loadpilot.backend.service.keycloak.KeycloakUserRecord;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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

    @Test
    void list_asSuperAdmin_whenKeycloakAdminUnreachable_returns502() throws Exception {
        when(keycloakAdminClient.listUsers()).thenThrow(new KeycloakAdminException("Administration Keycloak injoignable."));

        mockMvc.perform(get("/api/users").with(AS_SUPER_ADMIN))
                .andExpect(status().isBadGateway());
    }
}
