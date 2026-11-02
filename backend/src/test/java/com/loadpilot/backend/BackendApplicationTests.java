package com.loadpilot.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;

/** Le contexte Spring demarre avec le profil "test" (H2, Liquibase, securite). */
@SpringBootTest
@ActiveProfiles("test")
class BackendApplicationTests {

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void contextLoads() {
        // Le demarrage du contexte suffit.
    }
}
