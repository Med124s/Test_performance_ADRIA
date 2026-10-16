package com.loadpilot.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Correction P1 - chiffrement AES-256-GCM des secrets : aller-retour, integrite, cle absente ou invalide. */
class SecretCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final String OTHER_KEY = Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8));

    @Test
    void encrypt_thenDecrypt_returnsOriginal_withPrefix_andRandomIv() {
        SecretCipher cipher = new SecretCipher(KEY);
        String plain = "{\"token\":\"s3cret-token\",\"loginBody\":\"{\\\"password\\\":\\\"P@ss-éè\\\"}\"}";

        String a = cipher.encrypt(plain);
        String b = cipher.encrypt(plain);

        assertThat(a).startsWith(SecretCipher.PREFIX).doesNotContain("s3cret-token").doesNotContain("P@ss");
        assertThat(a).as("IV aleatoire : deux chiffrements differents").isNotEqualTo(b);
        assertThat(cipher.decrypt(a)).isEqualTo(plain);
        assertThat(cipher.decrypt(b)).isEqualTo(plain);
    }

    @Test
    void nullAndEmpty_stayAsIs_alreadyEncrypted_neverReEncrypted_legacyPlain_readAsIs() {
        SecretCipher cipher = new SecretCipher(KEY);
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.encrypt("")).isEmpty();
        assertThat(cipher.decrypt(null)).isNull();
        assertThat(cipher.decrypt("")).isEmpty();
        String once = cipher.encrypt("x");
        assertThat(cipher.encrypt(once)).isEqualTo(once);
        assertThat(cipher.decrypt("ancienne-valeur-en-clair")).isEqualTo("ancienne-valeur-en-clair");
    }

    @Test
    void wrongKey_orTamperedData_clearError_withoutValueNorKey() {
        String stored = new SecretCipher(KEY).encrypt("s3cret-token");
        SecretCipher other = new SecretCipher(OTHER_KEY);
        assertThatThrownBy(() -> other.decrypt(stored))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LOADPILOT_SECRETS_KEY")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("s3cret").doesNotContain(OTHER_KEY).doesNotContain(KEY));

        SecretCipher cipher = new SecretCipher(KEY);
        char last = stored.charAt(stored.length() - 2);
        String tampered = stored.substring(0, stored.length() - 2) + (last == 'A' ? 'B' : 'A') + stored.charAt(stored.length() - 1);
        assertThatThrownBy(() -> cipher.decrypt(tampered)).hasMessageContaining("alteree");
    }

    @Test
    void missingOrInvalidKey_refusesToStart_withClearMessage() {
        assertThatThrownBy(() -> new SecretCipher(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LOADPILOT_SECRETS_KEY est absente")
                .hasMessageContaining("refuse de demarrer");
        assertThatThrownBy(() -> new SecretCipher(null)).hasMessageContaining("absente");
        assertThatThrownBy(() -> new SecretCipher("pas du base64 !")).hasMessageContaining("n'est pas du Base64 valide");
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new SecretCipher(shortKey))
                .hasMessageContaining("exactement 32 octets")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(shortKey));
    }

    @Test
    void springContext_withoutKey_failsToStart() {
        new ApplicationContextRunner()
                .withUserConfiguration(SecretCipher.class)
                .withPropertyValues("app.secrets.encryption-key=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("LOADPILOT_SECRETS_KEY est absente");
                });
    }
}
