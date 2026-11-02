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

/** Documentation OpenAPI publique, API metier protegee, et Actuator limite a health. */
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
        // show-details=never : le corps ne contient que "status".
        String body = mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode node = objectMapper.readTree(body);
        assertThat(node.fieldNames()).toIterable().containsExactly("status");
    }

    // Endpoint absent de PUBLIC_ENDPOINTS : 401 sans jeton.

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
        assertThat(paths.has("/api/users")).isTrue();

        // Modules non implementes absents de la documentation.
        assertThat(paths.has("/api/reports")).isFalse();
        assertThat(paths.has("/api/admin/settings")).isFalse();
    }

    @Test
    void apiDocs_declaresGlobalBearerSecurityRequirement() throws Exception {
        // Exigence de securite definie a la racine du document, heritee par chaque operation.
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
        // /swagger-ui.html redirige generalement vers /swagger-ui/index.html ; jamais 401 ni 403.
        assertThat(status).isNotIn(401, 403);
    }

    @Test
    void businessEndpoint_stillRequiresAuthentication_despiteSwaggerBeingPublic() throws Exception {
        // L'API metier reste protegee.
        mockMvc.perform(get("/api/applications")).andExpect(status().isUnauthorized());
    }
}
