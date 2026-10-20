package com.loadpilot.backend.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

/** Temps de reponse maximal et assertion JSON d'une etape : enregistres, relus, et valides par l'API. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StepChecksApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_ENGINEER =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_PERFORMANCE_ENGINEER"));

    private String createScenario(String name) throws Exception {
        String app = mockMvc.perform(post("/api/applications").with(AS_ENGINEER).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ApplicationRequest(name, "d", "http://example.com/" + name))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String scenario = mockMvc.perform(post("/api/scenarios").with(AS_ENGINEER).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ScenarioRequest(
                                UUID.fromString(objectMapper.readTree(app).get("id").asText()), name, "d"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(scenario).get("id").asText();
    }

    private ObjectNode step(String scenarioId) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("scenarioId", scenarioId);
        node.put("name", "Solde");
        node.put("method", "GET");
        node.put("url", "/api/balance");
        node.put("order", 1);
        return node;
    }

    @Test
    void checks_arePersistedAndReturned_andOmittedMeansNone() throws Exception {
        String scenarioId = createScenario("step-checks-app");
        ObjectNode node = step(scenarioId);
        node.put("maxResponseTimeMs", 800);
        node.put("assertionJsonPath", "$.status");
        node.put("assertionJsonOperator", "CONTAINS");
        node.put("assertionJsonValue", "OK");
        String created = mockMvc.perform(post("/api/steps").with(AS_ENGINEER).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(node)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.maxResponseTimeMs").value(800))
                .andExpect(jsonPath("$.assertionJsonPath").value("$.status"))
                .andExpect(jsonPath("$.assertionJsonOperator").value("CONTAINS"))
                .andExpect(jsonPath("$.assertionJsonValue").value("OK"))
                .andReturn().getResponse().getContentAsString();
        String stepId = objectMapper.readTree(created).get("id").asText();

        mockMvc.perform(get("/api/steps/" + stepId).with(AS_ENGINEER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxResponseTimeMs").value(800));

        // Client existant qui n'envoie pas ces champs : aucun controle supplementaire.
        mockMvc.perform(put("/api/steps/" + stepId).with(AS_ENGINEER).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(step(scenarioId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxResponseTimeMs").doesNotExist())
                .andExpect(jsonPath("$.assertionJsonPath").doesNotExist());
    }

    @Test
    void invalidChecks_areRejected_withAReadableMessage() throws Exception {
        String scenarioId = createScenario("step-checks-invalid-app");

        ObjectNode zero = step(scenarioId);
        zero.put("maxResponseTimeMs", 0);
        mockMvc.perform(post("/api/steps").with(AS_ENGINEER).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(zero)))
                .andExpect(status().isBadRequest());

        ObjectNode badPath = step(scenarioId);
        badPath.put("assertionJsonPath", "items[x]");
        badPath.put("assertionJsonValue", "1");
        mockMvc.perform(post("/api/steps").with(AS_ENGINEER).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badPath)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Assertion JSON : champ invalide (ex. $.status ou data.items[0].id)."));

        ObjectNode noPath = step(scenarioId);
        noPath.put("assertionJsonValue", "OK");
        mockMvc.perform(post("/api/steps").with(AS_ENGINEER).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(noPath)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Assertion JSON : indiquez le champ à vérifier (ex. $.status)."));
    }
}
