package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.dto.request.ApplicationRequest;
import com.loadpilot.backend.dto.request.ExecutionRequest;
import com.loadpilot.backend.dto.request.ScenarioRequest;
import com.loadpilot.backend.dto.request.StepRequest;
import com.loadpilot.backend.enums.HttpMethod;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
 * P1-D — verifie GET/PATCH /api/notification-preferences (modele
 * "opt-out" reel) ET que desactiver un type SUPPRIME reellement la
 * generation de la Notification correspondante (voir
 * NotificationServiceImpl.create), pas seulement l'affichage.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationPreferenceControllerTest {

    private static HttpServer targetServer;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockBean
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor asUser(String subject) {
        return jwt().jwt(builder -> builder.subject(subject).claim("preferred_username", subject))
                .authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
    }

    @BeforeAll
    static void startTargetServer() throws IOException {
        targetServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        targetServer.createContext("/ok", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        targetServer.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }));
        targetServer.start();
    }

    @AfterAll
    static void stopTargetServer() {
        targetServer.stop(0);
    }

    private String targetBaseUrl() {
        return "http://localhost:" + targetServer.getAddress().getPort();
    }

    @Test
    void list_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/notification-preferences")).andExpect(status().isUnauthorized());
    }

    @Test
    void list_defaultsAllFiveTypesToEnabled_optOutModel() throws Exception {
        RequestPostProcessor user = asUser("notif-pref-default-" + UUID.randomUUID());

        String response = mockMvc.perform(get("/api/notification-preferences").with(user))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode content = objectMapper.readTree(response);
        assertThat(content).hasSize(5);
        for (JsonNode entry : content) {
            assertThat(entry.get("enabled").asBoolean()).isTrue();
        }
    }

    @Test
    void setEnabled_false_thenList_reflectsTheChange_onlyForThatType() throws Exception {
        RequestPostProcessor user = asUser("notif-pref-toggle-" + UUID.randomUUID());

        mockMvc.perform(patch("/api/notification-preferences/SCHEDULE_TRIGGERED").with(user)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("SCHEDULE_TRIGGERED"))
                .andExpect(jsonPath("$.enabled").value(false));

        String response = mockMvc.perform(get("/api/notification-preferences").with(user))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode content = objectMapper.readTree(response);
        for (JsonNode entry : content) {
            boolean isTargetType = entry.get("type").asText().equals("SCHEDULE_TRIGGERED");
            assertThat(entry.get("enabled").asBoolean()).isEqualTo(!isTargetType);
        }
    }

    @Test
    void twoDifferentUsers_haveIndependentPreferences() throws Exception {
        RequestPostProcessor userA = asUser("notif-pref-iso-a-" + UUID.randomUUID());
        RequestPostProcessor userB = asUser("notif-pref-iso-b-" + UUID.randomUUID());

        mockMvc.perform(patch("/api/notification-preferences/EXECUTION_FAILED").with(userA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk());

        String responseB = mockMvc.perform(get("/api/notification-preferences").with(userB))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode contentB = objectMapper.readTree(responseB);
        for (JsonNode entry : contentB) {
            // userB n'a jamais rien desactive - tout doit rester actif,
            // jamais affecte par le changement de userA.
            assertThat(entry.get("enabled").asBoolean()).isTrue();
        }
    }

    /**
     * P1-D — verifie que la preference est REELLEMENT respectee par
     * NotificationServiceImpl : un utilisateur ayant coupe EXECUTION_SUCCESS
     * ne recoit AUCUNE notification de ce type apres une execution reelle
     * SUCCESS, alors qu'une notification aurait normalement ete generee
     * (voir NotificationControllerTest de P1-B pour ce comportement par
     * defaut).
     */
    @Test
    void disabledNotificationType_isNeverGenerated_forARealExecution() throws Exception {
        RequestPostProcessor user = asUser("notif-pref-suppress-" + UUID.randomUUID());

        mockMvc.perform(patch("/api/notification-preferences/EXECUTION_SUCCESS").with(user)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk());

        String appBody = objectMapper.writeValueAsString(new ApplicationRequest("notif-pref-app", "d", targetBaseUrl()));
        String appResponse = mockMvc.perform(post("/api/applications").with(user)
                        .contentType(MediaType.APPLICATION_JSON).content(appBody))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String appId = objectMapper.readTree(appResponse).get("id").asText();

        String scenarioBody = objectMapper.writeValueAsString(new ScenarioRequest(UUID.fromString(appId), "notif-pref-scenario", "d"));
        String scenarioResponse = mockMvc.perform(post("/api/scenarios").with(user)
                        .contentType(MediaType.APPLICATION_JSON).content(scenarioBody))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String scenarioId = objectMapper.readTree(scenarioResponse).get("id").asText();

        String stepBody = objectMapper.writeValueAsString(
                new StepRequest(UUID.fromString(scenarioId), "notif-pref-step", HttpMethod.GET, "/ok", null, null, 1, 200,
                        null, null, null, null, null, null));
        mockMvc.perform(post("/api/steps").with(user).contentType(MediaType.APPLICATION_JSON).content(stepBody))
                .andExpect(status().isCreated());

        String execBody = objectMapper.writeValueAsString(new ExecutionRequest(UUID.fromString(scenarioId)));
        String execResponse = mockMvc.perform(post("/api/executions").with(user)
                        .contentType(MediaType.APPLICATION_JSON).content(execBody))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String executionId = objectMapper.readTree(execResponse).get("id").asText();

        long deadline = System.currentTimeMillis() + 5000;
        String execStatus;
        do {
            execStatus = objectMapper.readTree(
                    mockMvc.perform(get("/api/executions/" + executionId).with(user))
                            .andReturn().getResponse().getContentAsString()).get("status").asText();
            if (!execStatus.equals("QUEUED") && !execStatus.equals("RUNNING")) break;
            Thread.sleep(20);
        } while (System.currentTimeMillis() < deadline);
        assertThat(execStatus).isEqualTo("SUCCESS");

        String notifResponse = mockMvc.perform(get("/api/notifications").with(user))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode notifContent = objectMapper.readTree(notifResponse).get("content");
        for (JsonNode n : notifContent) {
            assertThat(n.get("relatedExecutionId").asText()).isNotEqualTo(executionId);
        }
    }
}
