package com.loadpilot.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Verifie l'integration Swagger/OpenAPI (Phase 13) et le durcissement
 * d'Actuator, avec le contexte complet de l'application (jamais un slice) :
 * - /v3/api-docs et /swagger-ui.html sont publics (documentation, pas les
 *   endpoints metier eux-memes - voir SecurityConfig) ;
 * - le document OpenAPI genere reflete reellement le code (titre/version,
 *   schema Bearer JWT, endpoints des controllers existants) ;
 * - /actuator/health est public et minimal (aucun detail interne) ;
 * - aucun autre endpoint Actuator (info/env/beans/...) n'est expose.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SwaggerAndActuatorTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtDecoder jwtDecoder;

    // ------------------------------------------------------------
    // Actuator
    // ------------------------------------------------------------

    @Test
    void actuatorHealth_isPubliclyAccessibleWithoutToken() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void actuatorHealth_exposesOnlyTopLevelStatus_noInternalDetails() throws Exception {
        // show-details=never (voir application.yml) : le corps ne doit
        // contenir QUE la cle "status", jamais de sous-composants (db,
        // diskSpace, liquibase...) qui reveleraient des details internes.
        String body = mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode node = objectMapper.readTree(body);
        assertThat(node.fieldNames()).toIterable().containsExactly("status");
    }

    // Ces endpoints ne sont PAS dans PUBLIC_ENDPOINTS : une requete non
    // authentifiee est donc deja bloquee en 401 par le filtre de securite,
    // avant meme d'atteindre Spring MVC - une premiere preuve qu'ils ne
    // sont jamais accessibles sans authentification. Pour prouver qu'ils ne
    // sont EN PLUS pas exposes du tout par Actuator (voir
    // management.endpoints.web.exposure.include=health uniquement), on
    // verifie qu'un utilisateur authentifie SUPER_ADMIN obtient bien 404
    // (aucun mapping Actuator enregistre pour ce chemin), jamais 200.

    @Test
    void actuatorInfo_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
    }

    @Test
    void actuatorInfo_evenAsSuperAdmin_isNotExposed_returns404() throws Exception {
        mockMvc.perform(get("/actuator/info")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void actuatorEnv_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }

    @Test
    void actuatorEnv_evenAsSuperAdmin_isNotExposed_returns404() throws Exception {
        mockMvc.perform(get("/actuator/env")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void actuatorBeans_evenAsSuperAdmin_isNotExposed_returns404() throws Exception {
        mockMvc.perform(get("/actuator/beans")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void actuatorConfigprops_evenAsSuperAdmin_isNotExposed_returns404() throws Exception {
        mockMvc.perform(get("/actuator/configprops")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------
    // OpenAPI / Swagger
    // ------------------------------------------------------------

    @Test
    void apiDocs_isPubliclyAccessibleWithoutToken_andReturnsValidOpenApiDocument() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(root.get("openapi")).isNotNull();
        assertThat(root.get("info").get("title").asText()).isEqualTo("LoadPilot API");
        assertThat(root.get("info").get("version").asText()).isEqualTo("1.0.0");
    }

    @Test
    void apiDocs_declaresBearerJwtSecurityScheme() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode scheme = objectMapper.readTree(body).at("/components/securitySchemes/bearerAuth");
        assertThat(scheme.isMissingNode()).isFalse();
        assertThat(scheme.get("type").asText()).isEqualToIgnoringCase("http");
        assertThat(scheme.get("scheme").asText()).isEqualTo("bearer");
        assertThat(scheme.get("bearerFormat").asText()).isEqualTo("JWT");
    }

    @Test
    void apiDocs_listsRealControllerPaths_neverFictitiousOnes() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode paths = objectMapper.readTree(body).get("paths");
        assertThat(paths.has("/api/applications")).isTrue();
        assertThat(paths.has("/api/scenarios")).isTrue();
        assertThat(paths.has("/api/steps")).isTrue();
        assertThat(paths.has("/api/executions")).isTrue();
        assertThat(paths.has("/api/metrics")).isTrue();
        assertThat(paths.has("/api/dashboard")).isTrue();
        assertThat(paths.has("/api/audit-logs")).isTrue();
        assertThat(paths.has("/api/profile")).isTrue();
        // Phase 25 : /api/users existe reellement desormais (UserController,
        // administration Keycloak reelle) - retire de la liste des modules
        // non implementes ci-dessous.
        assertThat(paths.has("/api/users")).isTrue();

        // Aucun module futur/non implemente ne doit apparaitre comme un
        // endpoint reel (voir Phase 13, section 43).
        assertThat(paths.has("/api/reports")).isFalse();
        assertThat(paths.has("/api/admin/settings")).isFalse();
    }

    @Test
    void apiDocs_declaresGlobalBearerSecurityRequirement() throws Exception {
        // Requirement pose au niveau racine du document (OpenApiConfig) :
        // s'applique par heritage a toute operation qui ne le redefinit pas
        // explicitement - c'est le mecanisme OpenAPI standard, pas une
        // copie manuelle dans chaque operation.
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode security = objectMapper.readTree(body).get("security");
        assertThat(security).isNotNull();
        assertThat(security.toString()).contains("bearerAuth");
    }

    @Test
    void swaggerUiHtml_isAccessibleWithoutToken() throws Exception {
        int status = mockMvc.perform(get("/swagger-ui.html")).andReturn().getResponse().getStatus();
        // SpringDoc redirige generalement /swagger-ui.html -> /swagger-ui/index.html (3xx) ;
        // jamais 401/403 dans tous les cas (documentation publique, voir SecurityConfig).
        assertThat(status).isNotIn(401, 403);
    }

    @Test
    void businessEndpoint_stillRequiresAuthentication_despiteSwaggerBeingPublic() throws Exception {
        // Preuve que rendre Swagger public n'a pas rendu l'API metier
        // publique par erreur (voir Phase 13, section 33).
        mockMvc.perform(get("/api/applications")).andExpect(status().isUnauthorized());
    }
}
