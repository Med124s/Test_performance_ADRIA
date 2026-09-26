package com.loadpilot.backend.security;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifie le SecurityFilterChain de bout en bout (401 / 403 / acces autorise
 * / mapping des roles / rejet d'un token invalide) sans dependre d'un
 * Keycloak reel : JwtDecoder est mocke, et jwt() (Spring Security Test)
 * simule un principal deja authentifie pour les scenarios de role.
 *
 * SecurityTestController (src/test/java) sert de cible neutre, en l'absence
 * de tout controleur metier reel a ce stade du projet.
 */
@WebMvcTest(controllers = SecurityTestController.class)
@Import({
        SecurityConfig.class,
        JwtAuthConverter.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class
})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    // NB: l'accessibilite reelle d'un chemin permitAll (ex: /actuator/health)
    // est verifiee dans ActuatorSecurityTest, avec le contexte complet -
    // /api/test/public n'existe pas comme regle permitAll dans SecurityConfig
    // (aucun controleur metier public n'existe encore a ce stade du projet),
    // donc pas de sens a le tester ici.

    @Test
    void protectedEndpoint_withoutToken_returns401WithJsonErrorBody() throws Exception {
        mockMvc.perform(get("/api/test/admin-only"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.path").value("/api/test/admin-only"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void protectedEndpoint_authenticatedWithWrongRole_returns403WithJsonErrorBody() throws Exception {
        mockMvc.perform(get("/api/test/admin-only")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.path").value("/api/test/admin-only"));
    }

    @Test
    void protectedEndpoint_authenticatedWithCorrectRole_isAccessible() throws Exception {
        mockMvc.perform(get("/api/test/admin-only")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    void protectedEndpoint_authenticatedWithOneOfMultipleAllowedRoles_isAccessible() throws Exception {
        mockMvc.perform(get("/api/test/engineer-or-admin")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_PERFORMANCE_ENGINEER"))))
                .andExpect(status().isOk());
    }

    @Test
    void realKeycloakStyleJwt_realmAccessRolesAreConvertedAndGrantAccess() throws Exception {
        // Emprunte le vrai chemin JwtAuthConverter (pas le raccourci jwt())
        // pour prouver que le mapping realm_access.roles -> ROLE_xxx
        // fonctionne de bout en bout via un JwtDecoder mocke.
        Jwt keycloakStyleJwt = Jwt.withTokenValue("valid-token")
                .header("alg", "none")
                .claim("realm_access", Map.of("roles", List.of("PERFORMANCE_ENGINEER")))
                .claim("preferred_username", "engineer1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        when(jwtDecoder.decode("valid-token")).thenReturn(keycloakStyleJwt);

        mockMvc.perform(get("/api/test/engineer-or-admin")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk());
    }

    @Test
    void invalidOrExpiredToken_isRejectedWith401() throws Exception {
        when(jwtDecoder.decode("bad-token")).thenThrow(new BadJwtException("Invalid/expired token"));

        mockMvc.perform(get("/api/test/admin-only")
                        .header("Authorization", "Bearer bad-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }
}
