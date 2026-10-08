package com.loadpilot.backend.service.keycloak;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Refus de Keycloak (politique de mot de passe) -> messages francais clairs, format reel observe sur Keycloak 26. */
class PasswordPolicyMessagesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private String french(String code, String description) {
        return PasswordPolicyMessages.fromKeycloakError("{\"error\":\"" + code + "\",\"error_description\":\"" + description + "\"}", mapper);
    }

    @Test
    void translatesEachPolicyRule_withTheRealmNumbers() {
        assertThat(french("invalidPasswordMinLengthMessage", "Invalid password: minimum length 8."))
                .isEqualTo("Le mot de passe doit contenir au moins 8 caractères.");
        assertThat(french("invalidPasswordMinUpperCaseCharsMessage", "Invalid password: must contain at least 1 upper case characters."))
                .isEqualTo("Le mot de passe doit contenir au moins 1 majuscule.");
        assertThat(french("invalidPasswordMinDigitsMessage", "Invalid password: must contain at least 2 numerical digits."))
                .isEqualTo("Le mot de passe doit contenir au moins 2 chiffres.");
        assertThat(french("invalidPasswordNotUsernameMessage", "Invalid password: must not be equal to the username."))
                .isEqualTo("Le mot de passe ne doit pas être identique au nom d'utilisateur.");
        assertThat(french("invalidPasswordSomethingNew", "x")).isEqualTo(PasswordPolicyMessages.GENERIC);
    }

    @Test
    void otherErrors_areNotPasswordPolicyErrors() {
        assertThat(PasswordPolicyMessages.fromKeycloakError("{\"errorMessage\":\"User exists with same username\"}", mapper)).isNull();
        assertThat(PasswordPolicyMessages.fromKeycloakError("pas du json", mapper)).isNull();
        assertThat(PasswordPolicyMessages.fromKeycloakError(null, mapper)).isNull();
    }
}
