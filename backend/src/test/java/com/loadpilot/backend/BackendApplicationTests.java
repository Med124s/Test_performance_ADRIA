package com.loadpilot.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Test de fondation : verifie que le contexte Spring demarre correctement
 * avec le profil "test" (H2 + Liquibase + securite), sans logique metier a
 * tester pour l'instant.
 *
 * Depuis la Phase 4, le SecurityFilterChain exige un bean JwtDecoder (voir
 * SecurityConfig.oauth2ResourceServer) ; comme aucun Keycloak n'est
 * disponible pour les tests, il est mocke ici pour permettre au contexte
 * complet de charger sans dependance reseau.
 */
@SpringBootTest
@ActiveProfiles("test")
class BackendApplicationTests {

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void contextLoads() {
        // Le fait que le contexte demarre (datasource H2 + Liquibase +
        // validation JPA + SecurityFilterChain) suffit a valider la
        // fondation de cette phase.
    }
}
