package com.loadpilot.backend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Identification Basic dans les tunnels HTTPS : autorisee au demarrage, sauf valeur explicite (-D). */
class BackendApplicationTest {

    private static final String KEY = "jdk.http.auth.tunneling.disabledSchemes";
    private final String saved = System.getProperty(KEY);

    @AfterEach
    void restore() {
        if (saved == null) System.clearProperty(KEY);
        else System.setProperty(KEY, saved);
    }

    @Test
    void basicIsAllowedInTunnels_whateverTheLaunch_butAnExplicitValueIsKept() {
        System.clearProperty(KEY);
        BackendApplication.allowBasicProxyAuthenticationInTunnels();
        assertThat(System.getProperty(KEY)).isEmpty();

        System.setProperty(KEY, "Digest");
        BackendApplication.allowBasicProxyAuthenticationInTunnels();
        assertThat(System.getProperty(KEY)).isEqualTo("Digest");
    }
}
