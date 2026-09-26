package com.loadpilot.backend.security;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Convertit un Jwt Keycloak en Authentication Spring Security.
 *
 * Lit les roles realm dans la revendication standard Keycloak
 * "realm_access.roles" et les transforme en authorities Spring prefixees
 * "ROLE_" (ex: "ROLE_SUPER_ADMIN") pour etre compatibles avec
 * hasRole()/@PreAuthorize("hasRole('SUPER_ADMIN')").
 *
 * CONFIRME EN PHASE 5 (specification explicite : realm_access.roles =
 * ["ROLE_VIEWER"] doit produire l'authority "ROLE_VIEWER", pas
 * "ROLE_ROLE_VIEWER") : les roles realm Keycloak portent DEJA le prefixe
 * "ROLE_" - ceci remplace l'hypothese inverse documentee en Phase 4. Le
 * prefixage ci-dessous est rendu idempotent (n'ajoute "ROLE_" que si absent)
 * pour rester tolerant si un role est un jour defini sans prefixe cote
 * Keycloak, sans jamais produire de double-prefixe.
 */
@Component
public class JwtAuthConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String REALM_ACCESS_CLAIM = "realm_access";
    private static final String ROLES_CLAIM = "roles";
    private static final String ROLE_PREFIX = "ROLE_";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = extractRealmRoles(jwt).stream()
                .map(this::toRoleAuthority)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toUnmodifiableSet());
        return new JwtAuthenticationToken(jwt, authorities, extractPrincipalName(jwt));
    }

    private String toRoleAuthority(String role) {
        return role.startsWith(ROLE_PREFIX) ? role : ROLE_PREFIX + role;
    }

    @SuppressWarnings("unchecked")
    private Collection<String> extractRealmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap(REALM_ACCESS_CLAIM);
        if (realmAccess == null) {
            return Collections.emptySet();
        }
        Object rolesClaim = realmAccess.get(ROLES_CLAIM);
        if (!(rolesClaim instanceof Collection<?> rawRoles)) {
            return Collections.emptySet();
        }
        return rawRoles.stream().map(String::valueOf).collect(Collectors.toUnmodifiableSet());
    }

    /** "preferred_username" (convention Keycloak) si present, sinon "sub". */
    private String extractPrincipalName(Jwt jwt) {
        String preferredUsername = jwt.getClaimAsString("preferred_username");
        return preferredUsername != null ? preferredUsername : jwt.getSubject();
    }
}
