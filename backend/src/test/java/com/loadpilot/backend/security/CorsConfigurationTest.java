package com.loadpilot.backend.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifie la configuration CORS reelle de SecurityConfig - jamais testee
 * jusqu'ici (Phase 14). "app.cors.allowed-origins" est stubbe explicitement
 * a une origine connue via @TestPropertySource : le profil "test" (voir
 * application-test.yml) ne le definit pas, et CorsProperties par defaut est
 * une liste VIDE (aucune origine autorisee) - un choix "secure by default"
 * deliberee (voir CorsProperties) qui rendrait ce test sans objet sans ce
 * stub.
 *
 * Preflight (OPTIONS) : Spring Security's CorsFilter (active via
 * SecurityConfig.corsConfigurationSource(), voir .cors(...)) traite et
 * termine entierement une requete preflight AVANT meme d'atteindre
 * l'autorisation - une origine non autorisee ne doit donc jamais recevoir
 * les en-tetes Access-Control-Allow-*, quel que soit le token fourni.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.cors.allowed-origins=http://localhost:3000")
class CorsConfigurationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void preflight_fromAllowedOrigin_reflectsThatOriginAndAllowsConfiguredMethods() throws Exception {
        mockMvc.perform(options("/api/applications")
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"))
                .andExpect(header().exists(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS));
    }

    @Test
    void preflight_fromDisallowedOrigin_neverReceivesAllowOriginHeader() throws Exception {
        var result = mockMvc.perform(options("/api/applications")
                        .header(HttpHeaders.ORIGIN, "http://evil.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andReturn();

        // Comportement exact (403 vs reponse sans en-tete CORS) laisse au
        // framework - la seule garantie de securite a verifier est qu'une
        // origine non autorisee ne recoit JAMAIS le header qui l'autoriserait.
        org.assertj.core.api.Assertions.assertThat(
                result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
    }

    @Test
    void actualRequest_fromAllowedOrigin_reflectsOriginOnARealAuthenticatedCall() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/applications")
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_VIEWER"))))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"));
    }
}
