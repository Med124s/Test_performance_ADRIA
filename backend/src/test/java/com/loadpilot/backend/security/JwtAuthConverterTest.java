package com.loadpilot.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** Les roles "realm_access.roles" d'un JWT Keycloak deviennent des authorities "ROLE_...". */
class JwtAuthConverterTest {

    private final JwtAuthConverter converter = new JwtAuthConverter();

    private Jwt.Builder baseJwt() {
        return Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
    }

    @Test
    void convert_mapsRealmAccessRolesToPrefixedRoleAuthorities() {
        Jwt jwt = baseJwt()
                .claim("realm_access", Map.of("roles", List.of("SUPER_ADMIN", "PERFORMANCE_ENGINEER")))
                .claim("preferred_username", "alice")
                .build();

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_SUPER_ADMIN", "ROLE_PERFORMANCE_ENGINEER");
        assertThat(token.getName()).isEqualTo("alice");
    }

    @Test
    void convert_missingRealmAccessClaim_producesNoAuthorities() {
        Jwt jwt = baseJwt().claim("sub", "bob-id").build();

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getAuthorities()).isEmpty();
        // Pas de "preferred_username" : repli sur "sub".
        assertThat(token.getName()).isEqualTo("bob-id");
    }

    @Test
    void convert_emptyRolesList_producesNoAuthorities() {
        Jwt jwt = baseJwt()
                .claim("realm_access", Map.of("roles", List.of()))
                .build();

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getAuthorities()).isEmpty();
    }

    // ---- Roles deja prefixes "ROLE_" : pas de double prefixe ----

    @Test
    void convert_alreadyPrefixedViewerRole_producesRoleViewerWithoutDoublePrefix() {
        Jwt jwt = baseJwt().claim("realm_access", Map.of("roles", List.of("ROLE_VIEWER"))).build();

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_VIEWER");
    }

    @Test
    void convert_alreadyPrefixedPerformanceEngineerRole_producesRolePerformanceEngineerWithoutDoublePrefix() {
        Jwt jwt = baseJwt().claim("realm_access", Map.of("roles", List.of("ROLE_PERFORMANCE_ENGINEER"))).build();

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_PERFORMANCE_ENGINEER");
    }

    @Test
    void convert_alreadyPrefixedSuperAdminRole_producesRoleSuperAdminWithoutDoublePrefix() {
        Jwt jwt = baseJwt().claim("realm_access", Map.of("roles", List.of("ROLE_SUPER_ADMIN"))).build();

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_SUPER_ADMIN");
    }
}
