package com.loadpilot.backend.controller;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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

/**
 * Verifie GET /api/profile de bout en bout (SecurityFilterChain reel,
 * JwtAuthConverter reel, ProfileController -> ProfileServiceImpl ->
 * AppUserRepository sur H2 reel) - seul JwtDecoder est mocke, pour ne
 * dependre d'aucun Keycloak reel (voir Phase 4/5).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    private Jwt jwtFor(String tokenValue, String subject, String username, String name, String email,
                        List<String> realmRoles) {
        Jwt.Builder builder = Jwt.withTokenValue(tokenValue)
                .header("alg", "none")
                .subject(subject)
                .claim("realm_access", Map.of("roles", realmRoles))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
        if (username != null) builder.claim("preferred_username", username);
        if (name != null) builder.claim("name", name);
        if (email != null) builder.claim("email", email);
        return builder.build();
    }

    @Test
    void profile_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/profile"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void profile_withValidToken_returns200WithFullIdentity() throws Exception {
        Jwt jwt = jwtFor("token-alice", "sub-alice", "alice", "Alice Dupont", "alice@example.com",
                List.of("ROLE_PERFORMANCE_ENGINEER"));
        when(jwtDecoder.decode("token-alice")).thenReturn(jwt);

        mockMvc.perform(get("/api/profile").header("Authorization", "Bearer token-alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("sub-alice"))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.name").value("Alice Dupont"))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.roles").isArray())
                .andExpect(jsonPath("$.roles", containsInAnyOrder("ROLE_PERFORMANCE_ENGINEER")));
    }

    @Test
    void profile_neverReturnsRawJwtOrTokenField() throws Exception {
        Jwt jwt = jwtFor("token-bob", "sub-bob", "bob", null, null, List.of("ROLE_VIEWER"));
        when(jwtDecoder.decode("token-bob")).thenReturn(jwt);

        mockMvc.perform(get("/api/profile").header("Authorization", "Bearer token-bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.jwt").doesNotExist())
                .andExpect(jsonPath("$.claims").doesNotExist());
    }

    @Test
    void profile_realmAccessRoleViewer_producesRoleViewer() throws Exception {
        Jwt jwt = jwtFor("token-viewer", "sub-viewer", "vic", null, null, List.of("ROLE_VIEWER"));
        when(jwtDecoder.decode("token-viewer")).thenReturn(jwt);

        mockMvc.perform(get("/api/profile").header("Authorization", "Bearer token-viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", containsInAnyOrder("ROLE_VIEWER")));
    }

    @Test
    void profile_realmAccessRolePerformanceEngineer_producesRolePerformanceEngineer() throws Exception {
        Jwt jwt = jwtFor("token-engineer", "sub-engineer", "eng", null, null, List.of("ROLE_PERFORMANCE_ENGINEER"));
        when(jwtDecoder.decode("token-engineer")).thenReturn(jwt);

        mockMvc.perform(get("/api/profile").header("Authorization", "Bearer token-engineer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", containsInAnyOrder("ROLE_PERFORMANCE_ENGINEER")));
    }

    @Test
    void profile_realmAccessRoleSuperAdmin_producesRoleSuperAdmin() throws Exception {
        Jwt jwt = jwtFor("token-admin", "sub-admin", "root", null, null, List.of("ROLE_SUPER_ADMIN"));
        when(jwtDecoder.decode("token-admin")).thenReturn(jwt);

        mockMvc.perform(get("/api/profile").header("Authorization", "Bearer token-admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", containsInAnyOrder("ROLE_SUPER_ADMIN")));
    }

    @Test
    void profile_tokenWithoutPreferredUsernameClaim_returnsNullUsername_neverInvented() throws Exception {
        Jwt jwt = jwtFor("token-no-username", "sub-no-username", null, null, null, List.of("ROLE_VIEWER"));
        when(jwtDecoder.decode("token-no-username")).thenReturn(jwt);

        mockMvc.perform(get("/api/profile").header("Authorization", "Bearer token-no-username"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("sub-no-username"))
                .andExpect(jsonPath("$.username").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void profile_tokenWithoutEmailClaim_returnsNullEmail_neverInvented() throws Exception {
        Jwt jwt = jwtFor("token-no-email", "sub-no-email", "someuser", null, null, List.of("ROLE_VIEWER"));
        when(jwtDecoder.decode("token-no-email")).thenReturn(jwt);

        mockMvc.perform(get("/api/profile").header("Authorization", "Bearer token-no-email"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void profile_multipleRealmRoles_areAllReflectedInResponse() throws Exception {
        Jwt jwt = jwtFor("token-multi-role", "sub-multi", "multi", null, null,
                List.of("ROLE_SUPER_ADMIN", "ROLE_PERFORMANCE_ENGINEER"));
        when(jwtDecoder.decode("token-multi-role")).thenReturn(jwt);

        mockMvc.perform(get("/api/profile").header("Authorization", "Bearer token-multi-role"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", containsInAnyOrder("ROLE_SUPER_ADMIN", "ROLE_PERFORMANCE_ENGINEER")));
    }

    @Test
    void profile_realmRoleWithoutRolePrefix_isNormalizedWithRolePrefix() throws Exception {
        // JwtAuthConverter prefixe idempotemment (voir Phase 5) - un role
        // Keycloak fourni SANS prefixe "ROLE_" doit quand meme en obtenir un
        // seul, jamais un double-prefixe et jamais un role brut inutilisable.
        Jwt jwt = jwtFor("token-unprefixed", "sub-unprefixed", "raw", null, null, List.of("VIEWER"));
        when(jwtDecoder.decode("token-unprefixed")).thenReturn(jwt);

        mockMvc.perform(get("/api/profile").header("Authorization", "Bearer token-unprefixed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", containsInAnyOrder("ROLE_VIEWER")));
    }

    // ------------------------------------------------------------
    // P1-D — timezone (GET expose la valeur reelle persistee, PATCH la modifie)
    // ------------------------------------------------------------

    @Test
    void profile_neverSetTimezone_returnsNullTimezone_neverInvented() throws Exception {
        Jwt jwt = jwtFor("token-no-tz", "sub-no-tz", "notz", null, null, List.of("ROLE_VIEWER"));
        when(jwtDecoder.decode("token-no-tz")).thenReturn(jwt);

        mockMvc.perform(get("/api/profile").header("Authorization", "Bearer token-no-tz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void updateProfile_withoutToken_returns401() throws Exception {
        mockMvc.perform(patch("/api/profile").contentType(MediaType.APPLICATION_JSON).content("{\"timezone\":\"UTC\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateProfile_validTimezone_persistsAndIsReflectedByFollowingGet() throws Exception {
        Jwt jwt = jwtFor("token-tz-update", "sub-tz-update", "tzuser", null, null, List.of("ROLE_VIEWER"));
        when(jwtDecoder.decode("token-tz-update")).thenReturn(jwt);

        mockMvc.perform(patch("/api/profile").header("Authorization", "Bearer token-tz-update")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"timezone\":\"Africa/Casablanca\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Africa/Casablanca"));

        mockMvc.perform(get("/api/profile").header("Authorization", "Bearer token-tz-update"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Africa/Casablanca"));
    }

    @Test
    void updateProfile_invalidTimezone_returns400_neverSilentlyAccepted() throws Exception {
        Jwt jwt = jwtFor("token-tz-invalid", "sub-tz-invalid", "tzinvalid", null, null, List.of("ROLE_VIEWER"));
        when(jwtDecoder.decode("token-tz-invalid")).thenReturn(jwt);

        mockMvc.perform(patch("/api/profile").header("Authorization", "Bearer token-tz-invalid")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"timezone\":\"Not/AZone\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateProfile_neverAllowsChangingUsernameNameOrEmail() throws Exception {
        // Le DTO ProfileUpdateRequest n'expose meme pas ces champs - un
        // corps JSON qui tente de les fournir est simplement ignore (aucune
        // erreur, aucun effet), jamais silencieusement applique.
        Jwt jwt = jwtFor("token-tz-noop-fields", "sub-noop", "original", "Original Name", "original@example.com", List.of("ROLE_VIEWER"));
        when(jwtDecoder.decode("token-tz-noop-fields")).thenReturn(jwt);

        mockMvc.perform(patch("/api/profile").header("Authorization", "Bearer token-tz-noop-fields")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timezone\":\"UTC\",\"username\":\"hacked\",\"name\":\"Hacked Name\",\"email\":\"hacked@evil.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("original"))
                .andExpect(jsonPath("$.name").value("Original Name"))
                .andExpect(jsonPath("$.email").value("original@example.com"));
    }
}
