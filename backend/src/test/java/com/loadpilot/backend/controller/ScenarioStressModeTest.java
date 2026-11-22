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
import com.loadpilot.backend.dto.request.ExecutionRequest;
import com.loadpilot.backend.dto.request.ScheduledExecutionRequest;
import com.loadpilot.backend.dto.request.StepRequest;
import com.loadpilot.backend.enums.HttpMethod;
import com.loadpilot.backend.enums.ScheduleType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

/** Mode de charge d'un scenario : MANUAL par defaut, STRESS sans reglage de charge, jamais planifie. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScenarioStressModeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_SUPER_ADMIN =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));

    private String createApplication(String name) throws Exception {
        String body = objectMapper.writeValueAsString(new ApplicationRequest(name, "d", "http://example.com/" + name));
        String response = mockMvc.perform(post("/api/applications").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    /** Scenario dont la charge depasse les limites du serveur (200 utilisateurs par test). */
    private ObjectNode scenarioBeyondLimits(String applicationId, String name, String loadMode) {
        ObjectNode body = objectMapper.createObjectNode()
                .put("applicationId", applicationId)
                .put("name", name)
                .put("virtualUsers", 5000)
                .put("rampUpSeconds", 0)
                .put("thinkTimeMs", 0);
        if (loadMode != null) body.put("loadMode", loadMode);
        return body;
    }

    private String create(ObjectNode body) throws Exception {
        String response = mockMvc.perform(post("/api/scenarios").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private void addStep(String scenarioId) throws Exception {
        String body = objectMapper.writeValueAsString(new StepRequest(UUID.fromString(scenarioId), "step", HttpMethod.GET, "/ok",
                null, null, 1, 200, null, null, null, null, null, null));
        mockMvc.perform(post("/api/steps").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private String scheduleJson(String scenarioId) throws Exception {
        return objectMapper.writeValueAsString(new ScheduledExecutionRequest(UUID.fromString(scenarioId), "nuit",
                ScheduleType.ONE_TIME, null, Instant.now().plus(1, ChronoUnit.DAYS), "UTC"));
    }

    @Test
    void newScenario_isManualByDefault() throws Exception {
        String appId = createApplication("stress-default-app");
        ObjectNode body = objectMapper.createObjectNode().put("applicationId", appId).put("name", "par-defaut");

        mockMvc.perform(post("/api/scenarios").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.loadMode").value("MANUAL"));
    }

    @Test
    void stressScenario_ignoresLoadSettingsAndLimits_withoutWarnings() throws Exception {
        String appId = createApplication("stress-limits-app");

        mockMvc.perform(post("/api/scenarios").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(scenarioBeyondLimits(appId, "manuel", "MANUAL").toString()))
                .andExpect(status().isBadRequest());

        String id = create(scenarioBeyondLimits(appId, "stress", "STRESS"));
        mockMvc.perform(get("/api/scenarios/" + id).with(AS_SUPER_ADMIN))
                .andExpect(jsonPath("$.loadMode").value("STRESS"))
                .andExpect(jsonPath("$.loadWarnings").isEmpty());
    }

    @Test
    void update_withoutLoadMode_keepsIt_andBackToManualChecksTheLoadAgain() throws Exception {
        String appId = createApplication("stress-update-app");
        String id = create(scenarioBeyondLimits(appId, "stress-maj", "STRESS"));

        mockMvc.perform(put("/api/scenarios/" + id).with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(scenarioBeyondLimits(appId, "renomme", null).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("renomme"))
                .andExpect(jsonPath("$.loadMode").value("STRESS"));

        mockMvc.perform(put("/api/scenarios/" + id).with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(scenarioBeyondLimits(appId, "renomme", "MANUAL").toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void stressScenario_cannotBeScheduled() throws Exception {
        String appId = createApplication("stress-schedule-app");
        String id = create(scenarioBeyondLimits(appId, "stress-planif", "STRESS"));

        mockMvc.perform(post("/api/scheduled-executions").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleJson(id)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Un scénario en mode stress ne se planifie pas : lancez-le à la main."));
    }

    @Test
    void scheduledScenario_cannotSwitchToStress() throws Exception {
        String appId = createApplication("stress-switch-app");
        ObjectNode manual = objectMapper.createObjectNode().put("applicationId", appId).put("name", "planifie");
        String id = create(manual);
        mockMvc.perform(post("/api/scheduled-executions").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleJson(id)))
                .andExpect(status().isCreated());

        mockMvc.perform(put("/api/scenarios/" + id).with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(manual.put("loadMode", "STRESS").toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Ce scénario a 1 planification(s) : supprimez-les avant de passer en mode stress "
                        + "(un test de stress se lance à la main, il ne se planifie pas)."));
        mockMvc.perform(get("/api/scenarios/" + id).with(AS_SUPER_ADMIN)).andExpect(jsonPath("$.loadMode").value("MANUAL"));
    }

    @Test
    void stressLaunch_runsAlone_cannotBePaused_andCanBeCancelled() throws Exception {
        // Cible sur un port ferme : aucune requete ne sort de la machine.
        int closedPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        String body = objectMapper.writeValueAsString(new ApplicationRequest("stress-launch-app", "d", "http://localhost:" + closedPort));
        String appId = objectMapper.readTree(mockMvc.perform(post("/api/applications").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        String stressId = create(scenarioBeyondLimits(appId, "stress-lancement", "STRESS"));
        addStep(stressId);
        String manualId = create(objectMapper.createObjectNode().put("applicationId", appId).put("name", "manuel-pendant-stress"));
        addStep(manualId);

        String launched = mockMvc.perform(post("/api/executions").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ExecutionRequest(UUID.fromString(stressId)))))
                .andExpect(status().isAccepted())
                // Capacite de la montee : la limite d'utilisateurs par test, pas les 5 000 enregistres.
                .andExpect(jsonPath("$.virtualUsers").value(org.hamcrest.Matchers.lessThanOrEqualTo(200)))
                .andReturn().getResponse().getContentAsString();
        String executionId = objectMapper.readTree(launched).get("id").asText();
        try {
            mockMvc.perform(post("/api/executions").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new ExecutionRequest(UUID.fromString(manualId)))))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.message").value("Un test de stress est en cours : il tourne seul sur le serveur. "
                            + "Réessayez quand il sera terminé."));

            waitForStatus(executionId, "RUNNING");
            mockMvc.perform(post("/api/executions/" + executionId + "/pause").with(AS_SUPER_ADMIN))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Un test de stress ne se met pas en pause (la comparaison à la baseline "
                            + "serait faussée) : laissez-le se terminer ou annulez-le."));
        } finally {
            mockMvc.perform(post("/api/executions/" + executionId + "/cancel").with(AS_SUPER_ADMIN));
        }
        waitForStatus(executionId, "CANCELLED");
    }

    private void waitForStatus(String executionId, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        String current = null;
        while (System.currentTimeMillis() < deadline) {
            current = objectMapper.readTree(mockMvc.perform(get("/api/executions/" + executionId).with(AS_SUPER_ADMIN))
                    .andReturn().getResponse().getContentAsString()).get("status").asText();
            if (expected.equals(current)) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Statut attendu " + expected + ", obtenu " + current);
    }
}
