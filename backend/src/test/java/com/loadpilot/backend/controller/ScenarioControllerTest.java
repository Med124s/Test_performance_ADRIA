package com.loadpilot.backend.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.dto.request.ApplicationRequest;
import com.loadpilot.backend.dto.request.ScenarioRequest;
import java.util.UUID;
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
 * Verifie le module Scenarios de bout en bout (SecurityFilterChain reel,
 * ScenarioController -> ScenarioServiceImpl -> ScenarioRepository sur H2
 * reel). Seul JwtDecoder est mocke (aucun Keycloak reel necessaire).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScenarioControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_SUPER_ADMIN =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
    private static final RequestPostProcessor AS_ENGINEER =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_PERFORMANCE_ENGINEER"));
    private static final RequestPostProcessor AS_VIEWER =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"));

    private String createApplicationAndGetId(String name) throws Exception {
        String body = objectMapper.writeValueAsString(
                new ApplicationRequest(name, "Description", "http://example.com/" + name));
        String response = mockMvc.perform(post("/api/applications").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private String scenarioRequestJson(String applicationId, String name, String description) throws Exception {
        return objectMapper.writeValueAsString(
                new ScenarioRequest(UUID.fromString(applicationId), name, description));
    }

    private String createScenarioAndGetId(String applicationId, String name) throws Exception {
        String body = scenarioRequestJson(applicationId, name, "Description de test");
        String response = mockMvc.perform(post("/api/scenarios").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    // ------------------------------------------------------------
    // Securite
    // ------------------------------------------------------------

    @Test
    void list_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/scenarios")).andExpect(status().isUnauthorized());
    }

    @Test
    void create_withoutToken_returns401() throws Exception {
        String appId = createApplicationAndGetId("sec-post-app");
        mockMvc.perform(post("/api/scenarios").contentType(MediaType.APPLICATION_JSON)
                        .content(scenarioRequestJson(appId, "App", "desc")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void update_withoutToken_returns401() throws Exception {
        String appId = createApplicationAndGetId("sec-put-app");
        String scenarioId = createScenarioAndGetId(appId, "sec-put-scenario");
        mockMvc.perform(put("/api/scenarios/" + scenarioId).contentType(MediaType.APPLICATION_JSON)
                        .content(scenarioRequestJson(appId, "renamed", "desc")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void delete_withoutToken_returns401() throws Exception {
        String appId = createApplicationAndGetId("sec-delete-app");
        String scenarioId = createScenarioAndGetId(appId, "sec-delete-scenario");
        mockMvc.perform(delete("/api/scenarios/" + scenarioId)).andExpect(status().isUnauthorized());
    }

    @Test
    void viewerRole_canGetButNotWrite() throws Exception {
        String appId = createApplicationAndGetId("viewer-app");
        String scenarioId = createScenarioAndGetId(appId, "viewer-scenario");

        mockMvc.perform(get("/api/scenarios").with(AS_VIEWER)).andExpect(status().isOk());
        mockMvc.perform(get("/api/scenarios/" + scenarioId).with(AS_VIEWER)).andExpect(status().isOk());

        mockMvc.perform(post("/api/scenarios").with(AS_VIEWER).contentType(MediaType.APPLICATION_JSON)
                        .content(scenarioRequestJson(appId, "forbidden", "desc")))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/scenarios/" + scenarioId).with(AS_VIEWER).contentType(MediaType.APPLICATION_JSON)
                        .content(scenarioRequestJson(appId, "forbidden-update", "desc")))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/scenarios/" + scenarioId).with(AS_VIEWER)).andExpect(status().isForbidden());
    }

    @Test
    void engineerRole_canPostPutAndDelete() throws Exception {
        String appId = createApplicationAndGetId("engineer-scenario-app");

        String body = scenarioRequestJson(appId, "engineer-scenario", "desc");
        String response = mockMvc.perform(post("/api/scenarios").with(AS_ENGINEER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String scenarioId = objectMapper.readTree(response).get("id").asText();

        mockMvc.perform(put("/api/scenarios/" + scenarioId).with(AS_ENGINEER).contentType(MediaType.APPLICATION_JSON)
                        .content(scenarioRequestJson(appId, "engineer-scenario-renamed", "desc")))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/scenarios/" + scenarioId).with(AS_ENGINEER)).andExpect(status().isNoContent());
    }

    @Test
    void superAdminRole_canPostPutAndDelete() throws Exception {
        String appId = createApplicationAndGetId("admin-scenario-app");
        String scenarioId = createScenarioAndGetId(appId, "admin-scenario");

        mockMvc.perform(put("/api/scenarios/" + scenarioId).with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(scenarioRequestJson(appId, "admin-scenario-renamed", "desc")))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/scenarios/" + scenarioId).with(AS_SUPER_ADMIN)).andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------

    @Test
    void create_missingApplicationId_returns400() throws Exception {
        String body = objectMapper.writeValueAsString(new ScenarioRequest(null, "name", "desc"));
        mockMvc.perform(post("/api/scenarios").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void create_missingName_returns400() throws Exception {
        String appId = createApplicationAndGetId("validation-missing-name-app");
        String body = objectMapper.writeValueAsString(new ScenarioRequest(UUID.fromString(appId), null, "desc"));
        mockMvc.perform(post("/api/scenarios").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_blankName_returns400() throws Exception {
        String appId = createApplicationAndGetId("validation-blank-name-app");
        mockMvc.perform(post("/api/scenarios").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(scenarioRequestJson(appId, "", "desc")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_nonexistentApplication_returns404() throws Exception {
        String body = scenarioRequestJson(UUID.randomUUID().toString(), "orphan-scenario", "desc");
        mockMvc.perform(post("/api/scenarios").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // ------------------------------------------------------------
    // CRUD
    // ------------------------------------------------------------

    @Test
    void create_returnsScenarioWithApplicationAndCreatedBy() throws Exception {
        String appId = createApplicationAndGetId("crud-create-app");
        mockMvc.perform(post("/api/scenarios").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(scenarioRequestJson(appId, "crud-create-scenario", "Ma description")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.applicationId").value(appId))
                .andExpect(jsonPath("$.applicationName").value("crud-create-app"))
                .andExpect(jsonPath("$.name").value("crud-create-scenario"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdBy").exists())
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void create_withCsvData_persistsAndReturnsItVerbatim() throws Exception {
        // P1-Q Etape B - CSV data source (variables ${nom} pour le moteur) :
        // round-trip complet requete -> persistence -> reponse.
        String appId = createApplicationAndGetId("csv-data-app");
        String csv = "username,password\nuser1,pass1\nuser2,pass2";
        var node = objectMapper.createObjectNode();
        node.put("applicationId", appId);
        node.put("name", "csv-data-scenario");
        node.put("description", "desc");
        node.put("csvData", csv);
        String body = objectMapper.writeValueAsString(node);

        mockMvc.perform(post("/api/scenarios").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.csvData").value(csv));
    }

    @Test
    void create_withoutCsvData_defaultsToNull_historicalBehaviorUnchanged() throws Exception {
        String appId = createApplicationAndGetId("no-csv-data-app");

        mockMvc.perform(post("/api/scenarios").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(scenarioRequestJson(appId, "no-csv-data-scenario", "desc")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.csvData").doesNotExist());
    }

    @Test
    void getById_returnsCreatedScenario() throws Exception {
        String appId = createApplicationAndGetId("crud-get-app");
        String scenarioId = createScenarioAndGetId(appId, "crud-get-scenario");

        mockMvc.perform(get("/api/scenarios/" + scenarioId).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(scenarioId));
    }

    @Test
    void list_includesCreatedScenario() throws Exception {
        String appId = createApplicationAndGetId("crud-list-app");
        String scenarioId = createScenarioAndGetId(appId, "crud-list-scenario");

        mockMvc.perform(get("/api/scenarios").with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + scenarioId + "')]").exists());
    }

    @Test
    void update_changesFields() throws Exception {
        String appId = createApplicationAndGetId("crud-update-app");
        String scenarioId = createScenarioAndGetId(appId, "crud-update-scenario");

        mockMvc.perform(put("/api/scenarios/" + scenarioId).with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(scenarioRequestJson(appId, "crud-update-scenario-renamed", "nouvelle description")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("crud-update-scenario-renamed"))
                .andExpect(jsonPath("$.description").value("nouvelle description"));
    }

    @Test
    void delete_removesScenario() throws Exception {
        String appId = createApplicationAndGetId("crud-delete-app");
        String scenarioId = createScenarioAndGetId(appId, "crud-delete-scenario");

        mockMvc.perform(delete("/api/scenarios/" + scenarioId).with(AS_SUPER_ADMIN)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/scenarios/" + scenarioId).with(AS_VIEWER)).andExpect(status().isNotFound());
    }

    @Test
    void getById_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/api/scenarios/" + UUID.randomUUID()).with(AS_VIEWER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // ------------------------------------------------------------
    // Filtrage par application
    // ------------------------------------------------------------

    @Test
    void listByApplication_returnsOnlyScenariosOfThatApplication() throws Exception {
        String appA = createApplicationAndGetId("filter-app-a");
        String appB = createApplicationAndGetId("filter-app-b");
        String scenarioA = createScenarioAndGetId(appA, "filter-scenario-a");
        String scenarioB = createScenarioAndGetId(appB, "filter-scenario-b");

        mockMvc.perform(get("/api/scenarios").param("applicationId", appA).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + scenarioA + "')]").exists())
                .andExpect(jsonPath("$[?(@.id=='" + scenarioB + "')]").isEmpty());
    }

    @Test
    void listByApplication_unknownApplication_returns404() throws Exception {
        mockMvc.perform(get("/api/scenarios").param("applicationId", UUID.randomUUID().toString()).with(AS_VIEWER))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------
    // Suppression d'une Application ayant des Scenarios rattaches
    // ------------------------------------------------------------

    @Test
    void deleteApplication_withDependentScenario_returns409() throws Exception {
        String appId = createApplicationAndGetId("conflict-app");
        createScenarioAndGetId(appId, "conflict-scenario");

        mockMvc.perform(delete("/api/applications/" + appId).with(AS_SUPER_ADMIN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }
}
