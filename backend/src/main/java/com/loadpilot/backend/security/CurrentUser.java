package com.loadpilot.backend.security;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Abstraction propre de l'utilisateur authentifie - evite de coupler les
 * services/controllers metier aux claims bruts du Jwt Keycloak partout dans
 * le code. Construite une seule fois par requete (voir {@link #from}),
 * jamais persistee telle quelle.
 *
 * "roles" reprend directement les authorities deja calculees par
 * {@link JwtAuthConverter} (ex: "ROLE_SUPER_ADMIN") - jamais re-derivees des
 * claims bruts une seconde fois, pour rester une unique source de verite.
 */
public record CurrentUser(
        String subject,
        String username,
        String name,
        String email,
        Set<String> roles
) {

    /**
     * Construit un CurrentUser a partir du Jwt (claims) et de
     * l'Authentication associee (authorities deja calculees par
     * JwtAuthConverter lors de l'authentification).
     */
    public static CurrentUser from(Jwt jwt, Authentication authentication) {
        Set<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toUnmodifiableSet());

        return new CurrentUser(
                jwt.getSubject(),
                jwt.getClaimAsString("preferred_username"),
                jwt.getClaimAsString("name"),
                jwt.getClaimAsString("email"),
                roles
        );
    }
}
