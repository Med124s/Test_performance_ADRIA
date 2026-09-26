package com.loadpilot.backend.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Garde-fou (Phase 5, point 10.G) : AppUser represente un utilisateur
 * authentifie via Keycloak et ne doit jamais porter de mot de passe/
 * credential/token - l'authentification reste integralement deleguee a
 * Keycloak. Ce test echoue immediatement si un tel champ est ajoute par
 * erreur plus tard.
 */
class AppUserEntityTest {

    private static final Set<String> FORBIDDEN_SUBSTRINGS = Set.of(
            "password", "credential", "refreshtoken", "clientsecret", "secret", "token"
    );

    @Test
    void appUser_hasNoPasswordOrCredentialLikeField() {
        List<String> offending = List.of(AppUser.class.getDeclaredFields()).stream()
                .map(Field::getName)
                .filter(name -> FORBIDDEN_SUBSTRINGS.stream().anyMatch(forbidden -> name.toLowerCase().contains(forbidden)))
                .toList();

        assertThat(offending)
                .as("AppUser ne doit contenir aucun champ de type mot de passe/credential/token")
                .isEmpty();
    }
}
