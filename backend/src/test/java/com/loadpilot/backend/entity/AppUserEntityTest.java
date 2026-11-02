package com.loadpilot.backend.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** AppUser ne porte ni mot de passe ni jeton : l'authentification est geree par Keycloak. */
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
