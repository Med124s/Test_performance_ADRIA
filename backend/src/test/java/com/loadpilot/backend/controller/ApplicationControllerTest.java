package com.loadpilot.backend.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.dto.request.ApplicationRequest;
import com.loadpilot.backend.service.http.HttpAvailabilityChecker;
import com.loadpilot.backend.service.http.HttpAvailabilityResult;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Verifie le module Applications de bout en bout (SecurityFilterChain reel,
 * ApplicationController -> ApplicationServiceImpl -> ApplicationRepository
 * sur H2 reel) - seuls JwtDecoder et HttpAvailabilityChecker sont mockes
 * (aucun Keycloak reel, aucun appel HTTP reel necessaire pour ces tests -
 * voir JavaHttpClientAvailabilityCheckerTest pour la preuve d'un vrai appel
 * HTTP).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApplicationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtDecoder jwtDecoder;

    @MockBean
    private HttpAvailabilityChecker availabilityChecker;

    private static final RequestPostProcessor AS_SUPER_ADMIN =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
    private static final RequestPostProcessor AS_ENGINEER =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_PERFORMANCE_ENGINEER"));
    private static final RequestPostProcessor AS_VIEWER =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"));

    @BeforeEach
    void defaultAvailabilityStub() {
        // Comportement par defaut (reponse HTTP 200) pour les tests qui ne
        // s'interessent qu'a l'autorisation, pas au resultat du test lui-meme.
        when(availabilityChecker.check(anyString(), any(Duration.class)))
                .thenReturn(new HttpAvailabilityResult(true, 200, 42L, null));
    }

    private String validRequestJson(String name, String url) throws Exception {
        return objectMapper.writeValueAsString(new ApplicationRequest(name, "Description de test", url));
    }

    private String createApplicationAndGetId(String name) throws Exception {
        String body = validRequestJson(name, "http://example.com/" + name);
        String response = mockMvc.perform(post("/api/applications").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    // ------------------------------------------------------------
    // Securite (1-9)
    // ------------------------------------------------------------

    @Test
    void list_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/applications")).andExpect(status().isUnauthorized());
    }

    @Test
    void create_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/api/applications").contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("App", "http://example.com")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void list_withViewerRole_returns200() throws Exception {
        mockMvc.perform(get("/api/applications").with(AS_VIEWER)).andExpect(status().isOk());
    }

    @Test
    void create_withViewerRole_returns403() throws Exception {
        mockMvc.perform(post("/api/applications").with(AS_VIEWER).contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("App", "http://example.com")))
                .andExpect(status().isForbidden());
    }

    @Test
    void update_withViewerRole_returns403() throws Exception {
        String id = createApplicationAndGetId("viewer-update-target");
        mockMvc.perform(put("/api/applications/" + id).with(AS_VIEWER).contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("Renamed", "http://example.com/renamed")))
                .andExpect(status().isForbidden());
    }

    @Test
    void delete_withViewerRole_returns403() throws Exception {
        String id = createApplicationAndGetId("viewer-delete-target");
        mockMvc.perform(delete("/api/applications/" + id).with(AS_VIEWER)).andExpect(status().isForbidden());
    }

    @Test
    void testAvailability_withViewerRole_returns403() throws Exception {
        String id = createApplicationAndGetId("viewer-test-target");
        mockMvc.perform(post("/api/applications/" + id + "/test").with(AS_VIEWER)).andExpect(status().isForbidden());
    }

    @Test
    void engineerRole_canListCreateUpdateAndTest() throws Exception {
        mockMvc.perform(get("/api/applications").with(AS_ENGINEER)).andExpect(status().isOk());

        String body = validRequestJson("engineer-app", "http://example.com/engineer");
        String response = mockMvc.perform(post("/api/applications").with(AS_ENGINEER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(response).get("id").asText();

        mockMvc.perform(put("/api/applications/" + id).with(AS_ENGINEER).contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("engineer-app-renamed", "http://example.com/engineer2")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/applications/" + id + "/test").with(AS_ENGINEER)).andExpect(status().isOk());
    }

    @Test
    void engineerRole_cannotDelete() throws Exception {
        String id = createApplicationAndGetId("engineer-delete-forbidden");
        mockMvc.perform(delete("/api/applications/" + id).with(AS_ENGINEER)).andExpect(status().isForbidden());
    }

    @Test
    void superAdminRole_canListCreateUpdateDeleteAndTest() throws Exception {
        mockMvc.perform(get("/api/applications").with(AS_SUPER_ADMIN)).andExpect(status().isOk());

        String id = createApplicationAndGetId("admin-app");

        mockMvc.perform(put("/api/applications/" + id).with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("admin-app-renamed", "http://example.com/admin2")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/applications/" + id + "/test").with(AS_SUPER_ADMIN)).andExpect(status().isOk());

        mockMvc.perform(delete("/api/applications/" + id).with(AS_SUPER_ADMIN)).andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------
    // Validation (10-14)
    // ------------------------------------------------------------

    @Test
    void create_blankName_returns400() throws Exception {
        mockMvc.perform(post("/api/applications").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("", "http://example.com")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void create_blankUrl_returns400() throws Exception {
        mockMvc.perform(post("/api/applications").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("App", "")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_malformedUrl_returns400() throws Exception {
        mockMvc.perform(post("/api/applications").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("App", "ceci-nest-pas-une-url")))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------
    // CRUD (15-20)
    // ------------------------------------------------------------

    @Test
    void create_returnsCreatedApplicationWithNoStatusYet() throws Exception {
        mockMvc.perform(post("/api/applications").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("brand-new-app", "http://example.com/new")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("brand-new-app"))
                .andExpect(jsonPath("$.status").value(nullValue()))
                .andExpect(jsonPath("$.createdBy").exists())
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void getById_returnsCreatedApplication() throws Exception {
        String id = createApplicationAndGetId("get-by-id-app");
        mockMvc.perform(get("/api/applications/" + id).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void list_includesCreatedApplication() throws Exception {
        String id = createApplicationAndGetId("list-includes-app");
        mockMvc.perform(get("/api/applications").with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + id + "')]").exists());
    }

    @Test
    void update_changesFields() throws Exception {
        String id = createApplicationAndGetId("update-target-app");
        mockMvc.perform(put("/api/applications/" + id).with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("updated-name", "http://example.com/updated")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("updated-name"))
                .andExpect(jsonPath("$.url").value("http://example.com/updated"));
    }

    @Test
    void delete_removesApplication() throws Exception {
        String id = createApplicationAndGetId("delete-target-app");
        mockMvc.perform(delete("/api/applications/" + id).with(AS_SUPER_ADMIN)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/applications/" + id).with(AS_VIEWER)).andExpect(status().isNotFound());
    }

    @Test
    void getById_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/api/applications/" + java.util.UUID.randomUUID()).with(AS_VIEWER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // ------------------------------------------------------------
    // Disponibilite (21, 22, 24) - le test bas niveau avec un vrai serveur
    // HTTP est dans JavaHttpClientAvailabilityCheckerTest.
    // ------------------------------------------------------------

    @Test
    void testAvailability_reachableTarget_setsConnectedStatus() throws Exception {
        String id = createApplicationAndGetId("connected-app");
        when(availabilityChecker.check(anyString(), any(Duration.class)))
                .thenReturn(new HttpAvailabilityResult(true, 200, 15L, null));

        mockMvc.perform(post("/api/applications/" + id + "/test").with(AS_SUPER_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONNECTED"))
                .andExpect(jsonPath("$.httpStatus").value(200));

        mockMvc.perform(get("/api/applications/" + id).with(AS_VIEWER))
                .andExpect(jsonPath("$.status").value("CONNECTED"));
    }

    @Test
    void testAvailability_httpErrorResponse_setsFailedStatus() throws Exception {
        String id = createApplicationAndGetId("failed-app");
        when(availabilityChecker.check(anyString(), any(Duration.class)))
                .thenReturn(new HttpAvailabilityResult(true, 500, 20L, null));

        mockMvc.perform(post("/api/applications/" + id + "/test").with(AS_SUPER_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.httpStatus").value(500));
    }

    @Test
    void testAvailability_technicalFailure_setsErrorStatus() throws Exception {
        String id = createApplicationAndGetId("error-app");
        when(availabilityChecker.check(anyString(), any(Duration.class)))
                .thenReturn(new HttpAvailabilityResult(false, null, 5000L, "Connection refused"));

        mockMvc.perform(post("/api/applications/" + id + "/test").with(AS_SUPER_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.httpStatus").value(nullValue()));
    }

}
