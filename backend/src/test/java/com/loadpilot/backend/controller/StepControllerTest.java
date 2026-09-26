package com.loadpilot.backend.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.dto.request.ApplicationRequest;
import com.loadpilot.backend.dto.request.ScenarioRequest;
import com.loadpilot.backend.enums.HttpMethod;
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
 * Verifie le module Steps de bout en bout (SecurityFilterChain reel,
 * StepController -> StepServiceImpl -> StepRepository sur H2 reel). Seul
 * JwtDecoder est mocke (aucun Keycloak reel necessaire).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StepControllerTest {

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

    private String createScenarioAndGetId(String applicationId, String name) throws Exception {
        String body = objectMapper.writeValueAsString(
                new ScenarioRequest(UUID.fromString(applicationId), name, "Description"));
        String response = mockMvc.perform(post("/api/scenarios").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private String stepRequestJson(String scenarioId, String name, HttpMethod method, String url,
                                    Integer order, Integer expectedStatus) throws Exception {
        var node = objectMapper.createObjectNode();
        node.put("scenarioId", scenarioId);
        node.put("name", name);
        node.put("method", method.name());
        node.put("url", url);
        node.putNull("headers");
        node.putNull("body");
        node.put("order", order);
        if (expectedStatus != null) node.put("expectedStatus", expectedStatus); else node.putNull("expectedStatus");
        return objectMapper.writeValueAsString(node);
    }

    private String createStepAndGetId(String scenarioId, String name, int order) throws Exception {
        String body = stepRequestJson(scenarioId, name, HttpMethod.GET, "/api/resource", order, 200);
        String response = mockMvc.perform(post("/api/steps").with(AS_SUPER_ADMIN)
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
        mockMvc.perform(get("/api/steps")).andExpect(status().isUnauthorized());
    }

    @Test
    void list_authenticated_returns200() throws Exception {
        mockMvc.perform(get("/api/steps").with(AS_VIEWER)).andExpect(status().isOk());
    }

    @Test
    void viewerRole_canGetButNotWrite() throws Exception {
        String appId = createApplicationAndGetId("viewer-step-app");
        String scenarioId = createScenarioAndGetId(appId, "viewer-step-scenario");
        String stepId = createStepAndGetId(scenarioId, "viewer-step", 1);

        mockMvc.perform(get("/api/steps").with(AS_VIEWER)).andExpect(status().isOk());
        mockMvc.perform(get("/api/steps/" + stepId).with(AS_VIEWER)).andExpect(status().isOk());

        String body = stepRequestJson(scenarioId, "forbidden", HttpMethod.GET, "/x", 2, null);
        mockMvc.perform(post("/api/steps").with(AS_VIEWER).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/steps/" + stepId).with(AS_VIEWER).contentType(MediaType.APPLICATION_JSON)
                        .content(stepRequestJson(scenarioId, "forbidden-update", HttpMethod.GET, "/x", 1, null)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/steps/" + stepId).with(AS_VIEWER)).andExpect(status().isForbidden());
    }

    @Test
    void engineerRole_canPostPutAndDelete() throws Exception {
        String appId = createApplicationAndGetId("engineer-step-app");
        String scenarioId = createScenarioAndGetId(appId, "engineer-step-scenario");

        String body = stepRequestJson(scenarioId, "engineer-step", HttpMethod.POST, "/api/login", 1, 200);
        String response = mockMvc.perform(post("/api/steps").with(AS_ENGINEER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String stepId = objectMapper.readTree(response).get("id").asText();

        mockMvc.perform(put("/api/steps/" + stepId).with(AS_ENGINEER).contentType(MediaType.APPLICATION_JSON)
                        .content(stepRequestJson(scenarioId, "engineer-step-renamed", HttpMethod.POST, "/api/login", 1, 201)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/steps/" + stepId).with(AS_ENGINEER)).andExpect(status().isNoContent());
    }

    @Test
    void superAdminRole_canPostPutAndDelete() throws Exception {
        String appId = createApplicationAndGetId("admin-step-app");
        String scenarioId = createScenarioAndGetId(appId, "admin-step-scenario");
        String stepId = createStepAndGetId(scenarioId, "admin-step", 1);

        mockMvc.perform(put("/api/steps/" + stepId).with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(stepRequestJson(scenarioId, "admin-step-renamed", HttpMethod.GET, "/api/resource", 1, 200)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/steps/" + stepId).with(AS_SUPER_ADMIN)).andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------

    @Test
    void create_missingScenarioId_returns400() throws Exception {
        var node = objectMapper.createObjectNode();
        node.putNull("scenarioId");
        node.put("name", "name");
        node.put("method", "GET");
        node.put("url", "/x");
        node.put("order", 1);
        mockMvc.perform(post("/api/steps").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(node)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void create_missingName_returns400() throws Exception {
        String appId = createApplicationAndGetId("val-missing-name-app");
        String scenarioId = createScenarioAndGetId(appId, "val-missing-name-scenario");
        var node = objectMapper.createObjectNode();
        node.put("scenarioId", scenarioId);
        node.putNull("name");
        node.put("method", "GET");
        node.put("url", "/x");
        node.put("order", 1);
        mockMvc.perform(post("/api/steps").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(node)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_blankName_returns400() throws Exception {
        String appId = createApplicationAndGetId("val-blank-name-app");
        String scenarioId = createScenarioAndGetId(appId, "val-blank-name-scenario");
        mockMvc.perform(post("/api/steps").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(stepRequestJson(scenarioId, "", HttpMethod.GET, "/x", 1, null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_missingMethod_returns400() throws Exception {
        String appId = createApplicationAndGetId("val-missing-method-app");
        String scenarioId = createScenarioAndGetId(appId, "val-missing-method-scenario");
        var node = objectMapper.createObjectNode();
        node.put("scenarioId", scenarioId);
        node.put("name", "step");
        node.putNull("method");
        node.put("url", "/x");
        node.put("order", 1);
        mockMvc.perform(post("/api/steps").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(node)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_urlWithWhitespace_returns400() throws Exception {
        String appId = createApplicationAndGetId("val-bad-url-app");
        String scenarioId = createScenarioAndGetId(appId, "val-bad-url-scenario");
        mockMvc.perform(post("/api/steps").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(stepRequestJson(scenarioId, "step", HttpMethod.GET, "/has space", 1, null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_blankUrl_returns400() throws Exception {
        String appId = createApplicationAndGetId("val-blank-url-app");
        String scenarioId = createScenarioAndGetId(appId, "val-blank-url-scenario");
        mockMvc.perform(post("/api/steps").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(stepRequestJson(scenarioId, "step", HttpMethod.GET, "", 1, null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_nonPositiveOrder_returns400() throws Exception {
        String appId = createApplicationAndGetId("val-order-app");
        String scenarioId = createScenarioAndGetId(appId, "val-order-scenario");
        mockMvc.perform(post("/api/steps").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(stepRequestJson(scenarioId, "step", HttpMethod.GET, "/x", 0, null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_invalidExpectedStatus_returns400() throws Exception {
        String appId = createApplicationAndGetId("val-expected-status-app");
        String scenarioId = createScenarioAndGetId(appId, "val-expected-status-scenario");
        mockMvc.perform(post("/api/steps").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(stepRequestJson(scenarioId, "step", HttpMethod.GET, "/x", 1, 999)))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------
    // Relation
    // ------------------------------------------------------------

    @Test
    void create_nonexistentScenario_returns404() throws Exception {
        String body = stepRequestJson(UUID.randomUUID().toString(), "orphan-step", HttpMethod.GET, "/x", 1, null);
        mockMvc.perform(post("/api/steps").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void update_nonexistentScenario_returns404() throws Exception {
        String appId = createApplicationAndGetId("rel-update-app");
        String scenarioId = createScenarioAndGetId(appId, "rel-update-scenario");
        String stepId = createStepAndGetId(scenarioId, "rel-update-step", 1);

        String body = stepRequestJson(UUID.randomUUID().toString(), "renamed", HttpMethod.GET, "/x", 1, null);
        mockMvc.perform(put("/api/steps/" + stepId).with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void listByScenario_unknownScenario_returns404() throws Exception {
        mockMvc.perform(get("/api/steps").param("scenarioId", UUID.randomUUID().toString()).with(AS_VIEWER))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------
    // CRUD
    // ------------------------------------------------------------

    @Test
    void create_returnsStepWithScenarioAndDefaults() throws Exception {
        String appId = createApplicationAndGetId("crud-create-app");
        String scenarioId = createScenarioAndGetId(appId, "crud-create-scenario");

        String body = stepRequestJson(scenarioId, "crud-create-step", HttpMethod.POST, "/api/login", 1, 200);
        mockMvc.perform(post("/api/steps").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.scenarioId").value(scenarioId))
                .andExpect(jsonPath("$.scenarioName").value("crud-create-scenario"))
                .andExpect(jsonPath("$.name").value("crud-create-step"))
                .andExpect(jsonPath("$.method").value("POST"))
                .andExpect(jsonPath("$.url").value("/api/login"))
                .andExpect(jsonPath("$.order").value(1))
                .andExpect(jsonPath("$.expectedStatus").value(200))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void create_withEngineOptions_persistsAndReturnsThemAll() throws Exception {
        // P1-Q Etape B - think time/timeout/follow-redirects/assertion PAR
        // ETAPE : verifie le round-trip complet (requete -> persistence ->
        // reponse), pas seulement que le champ est accepte.
        String appId = createApplicationAndGetId("engine-options-app");
        String scenarioId = createScenarioAndGetId(appId, "engine-options-scenario");

        var node = objectMapper.createObjectNode();
        node.put("scenarioId", scenarioId);
        node.put("name", "engine-options-step");
        node.put("method", "GET");
        node.put("url", "/api/x");
        node.putNull("headers");
        node.putNull("body");
        node.put("order", 1);
        node.put("expectedStatus", 200);
        node.put("thinkTimeMs", 500);
        node.put("timeoutSeconds", 5);
        node.put("followRedirects", false);
        node.put("assertionBodyContains", "\"status\":\"ok\"");
        String body = objectMapper.writeValueAsString(node);

        mockMvc.perform(post("/api/steps").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.thinkTimeMs").value(500))
                .andExpect(jsonPath("$.timeoutSeconds").value(5))
                .andExpect(jsonPath("$.followRedirects").value(false))
                .andExpect(jsonPath("$.assertionBodyContains").value("\"status\":\"ok\""));
    }

    @Test
    void create_withoutEngineOptions_defaultsAreAllNull_historicalBehaviorUnchanged() throws Exception {
        String appId = createApplicationAndGetId("engine-options-default-app");
        String scenarioId = createScenarioAndGetId(appId, "engine-options-default-scenario");
        String stepId = createStepAndGetId(scenarioId, "no-options-step", 1);

        mockMvc.perform(get("/api/steps/" + stepId).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thinkTimeMs").doesNotExist())
                .andExpect(jsonPath("$.timeoutSeconds").doesNotExist())
                .andExpect(jsonPath("$.followRedirects").doesNotExist())
                .andExpect(jsonPath("$.assertionBodyContains").doesNotExist());
    }

    @Test
    void create_negativeThinkTime_returns400() throws Exception {
        String appId = createApplicationAndGetId("engine-options-invalid-app");
        String scenarioId = createScenarioAndGetId(appId, "engine-options-invalid-scenario");

        var node = objectMapper.createObjectNode();
        node.put("scenarioId", scenarioId);
        node.put("name", "invalid-step");
        node.put("method", "GET");
        node.put("url", "/x");
        node.putNull("headers");
        node.putNull("body");
        node.put("order", 1);
        node.putNull("expectedStatus");
        node.put("thinkTimeMs", -1);
        String body = objectMapper.writeValueAsString(node);

        mockMvc.perform(post("/api/steps").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getById_returnsCreatedStep() throws Exception {
        String appId = createApplicationAndGetId("crud-get-app");
        String scenarioId = createScenarioAndGetId(appId, "crud-get-scenario");
        String stepId = createStepAndGetId(scenarioId, "crud-get-step", 1);

        mockMvc.perform(get("/api/steps/" + stepId).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(stepId));
    }

    @Test
    void listAll_includesCreatedStep() throws Exception {
        String appId = createApplicationAndGetId("crud-list-app");
        String scenarioId = createScenarioAndGetId(appId, "crud-list-scenario");
        String stepId = createStepAndGetId(scenarioId, "crud-list-step", 1);

        mockMvc.perform(get("/api/steps").with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + stepId + "')]").exists());
    }

    @Test
    void update_changesFields() throws Exception {
        String appId = createApplicationAndGetId("crud-update-app");
        String scenarioId = createScenarioAndGetId(appId, "crud-update-scenario");
        String stepId = createStepAndGetId(scenarioId, "crud-update-step", 1);

        String body = stepRequestJson(scenarioId, "crud-update-step-renamed", HttpMethod.DELETE, "/api/other", 5, 204);
        mockMvc.perform(put("/api/steps/" + stepId).with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("crud-update-step-renamed"))
                .andExpect(jsonPath("$.method").value("DELETE"))
                .andExpect(jsonPath("$.url").value("/api/other"))
                .andExpect(jsonPath("$.order").value(5))
                .andExpect(jsonPath("$.expectedStatus").value(204));
    }

    @Test
    void delete_removesStep() throws Exception {
        String appId = createApplicationAndGetId("crud-delete-app");
        String scenarioId = createScenarioAndGetId(appId, "crud-delete-scenario");
        String stepId = createStepAndGetId(scenarioId, "crud-delete-step", 1);

        mockMvc.perform(delete("/api/steps/" + stepId).with(AS_SUPER_ADMIN)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/steps/" + stepId).with(AS_VIEWER)).andExpect(status().isNotFound());
    }

    @Test
    void getById_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/api/steps/" + UUID.randomUUID()).with(AS_VIEWER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // ------------------------------------------------------------
    // Filtrage + ordre
    // ------------------------------------------------------------

    @Test
    void listByScenario_returnsOnlyStepsOfThatScenario() throws Exception {
        String appId = createApplicationAndGetId("filter-app");
        String scenarioA = createScenarioAndGetId(appId, "filter-scenario-a");
        String scenarioB = createScenarioAndGetId(appId, "filter-scenario-b");
        String stepA = createStepAndGetId(scenarioA, "filter-step-a", 1);
        String stepB = createStepAndGetId(scenarioB, "filter-step-b", 1);

        mockMvc.perform(get("/api/steps").param("scenarioId", scenarioA).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + stepA + "')]").exists())
                .andExpect(jsonPath("$[?(@.id=='" + stepB + "')]").isEmpty());
    }

    @Test
    void listByScenario_returnsStepsOrderedAscending() throws Exception {
        String appId = createApplicationAndGetId("order-app");
        String scenarioId = createScenarioAndGetId(appId, "order-scenario");
        // Crees volontairement dans le desordre.
        createStepAndGetId(scenarioId, "third", 3);
        createStepAndGetId(scenarioId, "first", 1);
        createStepAndGetId(scenarioId, "second", 2);

        String response = mockMvc.perform(get("/api/steps").param("scenarioId", scenarioId).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode array = objectMapper.readTree(response);
        org.assertj.core.api.Assertions.assertThat(array.get(0).get("name").asText()).isEqualTo("first");
        org.assertj.core.api.Assertions.assertThat(array.get(1).get("name").asText()).isEqualTo("second");
        org.assertj.core.api.Assertions.assertThat(array.get(2).get("name").asText()).isEqualTo("third");
    }

    // ------------------------------------------------------------
    // Suppression d'un Scenario ayant des Steps rattaches
    // ------------------------------------------------------------

    @Test
    void deleteScenario_withDependentStep_returns409() throws Exception {
        String appId = createApplicationAndGetId("conflict-app");
        String scenarioId = createScenarioAndGetId(appId, "conflict-scenario");
        createStepAndGetId(scenarioId, "conflict-step", 1);

        mockMvc.perform(delete("/api/scenarios/" + scenarioId).with(AS_SUPER_ADMIN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }
}
