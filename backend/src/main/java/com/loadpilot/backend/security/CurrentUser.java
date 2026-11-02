package com.loadpilot.backend.security;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** Utilisateur authentifie, construit a partir du JWT Keycloak. */
public record CurrentUser(
        String subject,
        String username,
        String name,
        String email,
        Set<String> roles,
        /** Prenom et nom (claims given_name / family_name), null si absents. */
        String firstName,
        String lastName
) {

    public static CurrentUser from(Jwt jwt, Authentication authentication) {
        Set<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toUnmodifiableSet());

        return new CurrentUser(
                jwt.getSubject(),
                jwt.getClaimAsString("preferred_username"),
                jwt.getClaimAsString("name"),
                jwt.getClaimAsString("email"),
                roles,
                jwt.getClaimAsString("given_name"),
                jwt.getClaimAsString("family_name")
        );
    }
}
