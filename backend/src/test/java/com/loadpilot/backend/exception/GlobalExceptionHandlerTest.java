package com.loadpilot.backend.exception;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Verifie GlobalExceptionHandler directement, exception par exception,
 * via ExceptionTestController (test-only, voir ce fichier) - plutot que de
 * s'appuyer uniquement sur la couverture incidente des controllers metier.
 * Contexte Spring complet (vraie SecurityFilterChain) : chaque requete est
 * authentifiee normalement, comme n'importe quel appel metier reel.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AUTHENTICATED =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));

    @Test
    void resourceNotFoundException_mapsTo404WithErrorShape() throws Exception {
        mockMvc.perform(get("/api/test/exceptions/not-found").with(AUTHENTICATED))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.message").value("Ressource de test introuvable."))
                .andExpect(jsonPath("$.path").value("/api/test/exceptions/not-found"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void conflictException_mapsTo409WithErrorShape() throws Exception {
        mockMvc.perform(get("/api/test/exceptions/conflict").with(AUTHENTICATED))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Conflit de test."));
    }

    @Test
    void dataIntegrityViolationException_mapsTo409_withGenericMessage_neverRawSqlDetails() throws Exception {
        mockMvc.perform(get("/api/test/exceptions/data-integrity").with(AUTHENTICATED))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(
                        "Cette operation viole une contrainte d'integrite (relation existante)."));
    }

    @Test
    void validationOnRequestBody_mapsTo400WithFieldError() throws Exception {
        mockMvc.perform(post("/api/test/exceptions/validate-body").with(AUTHENTICATED)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("name")));
    }

    @Test
    void malformedJsonBody_mapsTo400WithGenericMessage() throws Exception {
        mockMvc.perform(post("/api/test/exceptions/validate-body").with(AUTHENTICATED)
                        .contentType(MediaType.APPLICATION_JSON).content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Corps de requete JSON illisible ou malforme."));
    }

    @Test
    void constraintViolationOnRequestParam_mapsTo400() throws Exception {
        mockMvc.perform(get("/api/test/exceptions/validate-param").param("value", "-1").with(AUTHENTICATED))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void methodArgumentTypeMismatch_mapsTo400WithParameterName() throws Exception {
        mockMvc.perform(get("/api/test/exceptions/type-mismatch/not-a-uuid").with(AUTHENTICATED))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("id")));
    }

    @Test
    void unexpectedException_isNeverCaughtByGlobalExceptionHandler_confirmsDeliberateDesignChoice() {
        // GlobalExceptionHandler ne capture PAS Exception.class (voir sa
        // documentation - ne doit jamais intercepter AccessDeniedException/
        // AuthenticationException) : une exception non geree explicitement
        // se propage donc telle quelle ici. MockMvc (contrairement a un vrai
        // conteneur Tomcat) ne simule pas la redirection vers /error : on ne
        // peut donc pas observer ici la reponse 500 finale telle qu'un vrai
        // client HTTP la recevrait (deja securisee par les defauts Spring
        // Boot eux-memes : server.error.include-stacktrace/include-message
        // valent "never" par defaut, jamais configures autrement dans ce
        // projet) - ce test verifie seulement que GlobalExceptionHandler ne
        // l'intercepte pas silencieusement, confirmant le choix documente.
        assertThatThrownBy(() -> mockMvc.perform(get("/api/test/exceptions/unexpected").with(AUTHENTICATED)))
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void withoutAuthentication_stillReturns401_neverReachesTheHandlerOrController() throws Exception {
        mockMvc.perform(get("/api/test/exceptions/not-found"))
                .andExpect(status().isUnauthorized());
    }
}
