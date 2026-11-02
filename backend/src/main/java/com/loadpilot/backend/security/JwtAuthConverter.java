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

/** Convertit un JWT Keycloak en Authentication : les roles de "realm_access.roles" deviennent des authorities "ROLE_...". */
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
