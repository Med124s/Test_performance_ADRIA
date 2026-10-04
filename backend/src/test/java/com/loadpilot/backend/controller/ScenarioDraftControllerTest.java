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
import com.loadpilot.backend.dto.request.ScheduledExecutionRequest;
import com.loadpilot.backend.enums.ScheduleType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Brouillons de scenario (DRAFT) : creation, reprise, refus d'execution/planification, publication, suppression. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScenarioDraftControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockBean
    private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_SUPER_ADMIN = jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
    private static final RequestPostProcessor AS_ENGINEER = jwt().authorities(new SimpleGrantedAuthority("ROLE_PERFORMANCE_ENGINEER"));
    private static final RequestPostProcessor AS_VIEWER = jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"));

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
            RequestPostProcessor as, Object body) throws Exception {
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        return mockMvc.perform(builder.with(as));
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String createApplication(String name) throws Exception {
        return json(send(post("/api/applications"), AS_SUPER_ADMIN, Map.of("name", name, "url", "http://localhost:9"))
                .andExpect(status().isCreated())).get("id").asText();
    }

    private Map<String, Object> scenario(String appId, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("applicationId", appId);
        body.put("name", name);
        return body;
    }

    private String createDraft(String appId, String name) throws Exception {
        Map<String, Object> body = scenario(appId, name);
        body.put("draft", true);
        body.put("wizardStep", 1);
        body.put("draftState", "{\"version\":1,\"steps\":[{\"name\":\"\"}]}");
        return json(send(post("/api/scenarios"), AS_ENGINEER, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.wizardStep").value(1))
                .andExpect(jsonPath("$.draftState").value("{\"version\":1,\"steps\":[{\"name\":\"\"}]}")))
                .get("id").asText();
    }

    private Map<String, Object> step(String scenarioId, String url, String headers, String body) {
        Map<String, Object> step = new HashMap<>();
        step.put("scenarioId", scenarioId);
        step.put("name", "Etape");
        step.put("method", "POST");
        step.put("url", url);
        step.put("headers", headers);
        step.put("body", body);
        step.put("order", 1);
        return step;
    }

    @Test
    void draft_isListed_keepsWizardState_andCannotBeExecutedNorScheduled() throws Exception {
        String appId = createApplication("draft-app-1");
        String id = createDraft(appId, "Brouillon 1");

        mockMvc.perform(get("/api/scenarios").with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + id + "')].status").value("DRAFT"));

        // Mise a jour sans etat de l'assistant (ex. test) : l'etat enregistre est conserve.
        Map<String, Object> update = scenario(appId, "Brouillon 1 bis");
        send(put("/api/scenarios/" + id), AS_ENGINEER, update)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.wizardStep").value(1))
                .andExpect(jsonPath("$.draftState").exists());
        update.put("wizardStep", 2);
        send(put("/api/scenarios/" + id), AS_ENGINEER, update).andExpect(jsonPath("$.wizardStep").value(2));

        send(post("/api/executions"), AS_ENGINEER, Map.of("scenarioId", id))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("brouillon")));
        var schedule = new ScheduledExecutionRequest(UUID.fromString(id), "nuit", ScheduleType.ONE_TIME, null,
                Instant.now().plus(1, ChronoUnit.DAYS), "UTC");
        send(post("/api/scheduled-executions"), AS_SUPER_ADMIN, schedule)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("avant de le planifier")));
    }

    @Test
    void publish_validatesCompleteness_thenScenarioBecomesActive() throws Exception {
        String appId = createApplication("draft-app-2");
        Map<String, Object> body = scenario(appId, "A publier");
        body.put("draft", true);
        String id = json(send(post("/api/scenarios"), AS_ENGINEER, body)).get("id").asText();

        send(post("/api/scenarios/" + id + "/publish"), AS_ENGINEER, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Ajoutez au moins une étape avant d'enregistrer le scénario."));

        send(post("/api/steps"), AS_ENGINEER, step(id, "/api/transfers", "{\"Content-Type\":\"application/json\"}",
                "{\"amount\": ${amount}}")).andExpect(status().isCreated());
        body.put("iterations", 1);
        send(put("/api/scenarios/" + id), AS_ENGINEER, body).andExpect(status().isOk());

        send(post("/api/scenarios/" + id + "/publish"), AS_ENGINEER, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.wizardStep").doesNotExist())
                .andExpect(jsonPath("$.draftState").doesNotExist());

        // Un scenario actif ne passe jamais par la suppression de brouillon.
        send(delete("/api/scenarios/" + id + "/draft"), AS_ENGINEER, null).andExpect(status().isConflict());
    }

    @Test
    void deleteDraft_allowedForEngineer_forbiddenForViewer() throws Exception {
        String id = createDraft(createApplication("draft-app-3"), "A supprimer");

        send(delete("/api/scenarios/" + id + "/draft"), AS_VIEWER, null).andExpect(status().isForbidden());
        send(delete("/api/scenarios/" + id + "/draft"), AS_ENGINEER, null).andExpect(status().isNoContent());
        send(get("/api/scenarios/" + id), AS_VIEWER, null).andExpect(status().isNotFound());
    }

    @Test
    void invalidCsv_andInvalidSteps_areRejectedWithTheFrontendMessages() throws Exception {
        String appId = createApplication("draft-app-4");
        Map<String, Object> body = scenario(appId, "CSV");
        body.put("csvData", "user name,x\na,b");
        send(post("/api/scenarios"), AS_ENGINEER, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Nom de colonne invalide")));
        body.put("csvData", "﻿account;label\nACC001;\"a;b\"");
        String id = json(send(post("/api/scenarios"), AS_ENGINEER, body).andExpect(status().isCreated())).get("id").asText();

        send(post("/api/steps"), AS_ENGINEER, step(id, "ftp://x/y", null, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("URL invalide")));
        send(post("/api/steps"), AS_ENGINEER, step(id, "/x", "{\"Mon en-tete\":\"v\"}", null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Nom d'en-tête invalide")));
        send(post("/api/steps"), AS_ENGINEER, step(id, "/x", null, "{\"amount\": 12,}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Corps JSON invalide")));
        send(post("/api/steps"), AS_ENGINEER, step(id, "http://localhost:8888/api/x?id=${id}", null, "username=a&password=b"))
                .andExpect(status().isCreated());
    }
}
