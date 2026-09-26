package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * P1-B — verifie que les Notifications sont REELLEMENT generees depuis un
 * vrai evenement (fin d'Execution SUCCESS) ET que l'isolation entre
 * utilisateurs est stricte (jamais un utilisateur ne voit/modifie les
 * notifications d'un autre) - voir NotificationServiceImpl.
 *
 * Les postprocesseurs jwt() par defaut utilises ailleurs (AS_SUPER_ADMIN...)
 * partagent tous le meme "sub" par defaut - INSUFFISANT ici, ou l'identite
 * du declencheur doit reellement differer entre deux utilisateurs : ce
 * fichier construit donc ses propres Jwt avec un "sub" explicite et distinct.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationControllerTest {

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

    private String createScenarioWithSuccessfulStep(String prefix, RequestPostProcessor as) throws Exception {
        String appBody = objectMapper.writeValueAsString(new ApplicationRequest(prefix + "-app", "d", targetBaseUrl()));
        String appResponse = mockMvc.perform(post("/api/applications").with(as)
                        .contentType(MediaType.APPLICATION_JSON).content(appBody))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String appId = objectMapper.readTree(appResponse).get("id").asText();

        String scenarioBody = objectMapper.writeValueAsString(new ScenarioRequest(UUID.fromString(appId), prefix + "-scenario", "d"));
        String scenarioResponse = mockMvc.perform(post("/api/scenarios").with(as)
                        .contentType(MediaType.APPLICATION_JSON).content(scenarioBody))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String scenarioId = objectMapper.readTree(scenarioResponse).get("id").asText();

        String stepBody = objectMapper.writeValueAsString(
                new StepRequest(UUID.fromString(scenarioId), prefix + "-step", HttpMethod.GET, "/ok", null, null, 1, 200,
                        null, null, null, null, null, null));
        mockMvc.perform(post("/api/steps").with(as).contentType(MediaType.APPLICATION_JSON).content(stepBody))
                .andExpect(status().isCreated());
        return scenarioId;
    }

    private String executeAndWait(String scenarioId, RequestPostProcessor as) throws Exception {
        String body = objectMapper.writeValueAsString(new ExecutionRequest(UUID.fromString(scenarioId)));
        String response = mockMvc.perform(post("/api/executions").with(as)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String executionId = objectMapper.readTree(response).get("id").asText();

        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            String status = objectMapper.readTree(
                    mockMvc.perform(get("/api/executions/" + executionId).with(as))
                            .andReturn().getResponse().getContentAsString()).get("status").asText();
            if (!status.equals("QUEUED") && !status.equals("RUNNING")) return executionId;
            Thread.sleep(20);
        }
        throw new AssertionError("Execution " + executionId + " n'a pas termine a temps.");
    }

    private JsonNode listNotifications(RequestPostProcessor as) throws Exception {
        String response = mockMvc.perform(get("/api/notifications").with(as))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("content");
    }

    // ------------------------------------------------------------

    @Test
    void list_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
    }

    @Test
    void successfulExecution_generatesNotificationForTheUserWhoLaunchedIt() throws Exception {
        RequestPostProcessor userA = asUser("notif-user-a-" + UUID.randomUUID());
        String scenarioId = createScenarioWithSuccessfulStep("notif-success", userA);

        executeAndWait(scenarioId, userA);

        JsonNode content = listNotifications(userA);
        boolean found = false;
        for (JsonNode n : content) {
            if (n.get("type").asText().equals("EXECUTION_SUCCESS")) { found = true; break; }
        }
        assertThat(found).as("Une notification EXECUTION_SUCCESS doit exister pour l'utilisateur ayant lance l'execution").isTrue();
    }

    @Test
    void unreadCount_reflectsRealCountAndDecreasesAfterMarkRead() throws Exception {
        RequestPostProcessor user = asUser("notif-user-count-" + UUID.randomUUID());
        String scenarioId = createScenarioWithSuccessfulStep("notif-count", user);
        executeAndWait(scenarioId, user);

        String countResponse = mockMvc.perform(get("/api/notifications/unread-count").with(user))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long before = objectMapper.readTree(countResponse).get("count").asLong();
        assertThat(before).isGreaterThanOrEqualTo(1);

        JsonNode content = listNotifications(user);
        String firstId = content.get(0).get("id").asText();

        mockMvc.perform(patch("/api/notifications/" + firstId + "/read").with(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true))
                .andExpect(jsonPath("$.readAt").exists());

        String countAfterResponse = mockMvc.perform(get("/api/notifications/unread-count").with(user))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long after = objectMapper.readTree(countAfterResponse).get("count").asLong();
        assertThat(after).isEqualTo(before - 1);
    }

    @Test
    void markAllRead_marksEveryUnreadNotificationOfThatUser() throws Exception {
        RequestPostProcessor user = asUser("notif-user-markall-" + UUID.randomUUID());
        String scenarioA = createScenarioWithSuccessfulStep("notif-markall-a", user);
        String scenarioB = createScenarioWithSuccessfulStep("notif-markall-b", user);
        executeAndWait(scenarioA, user);
        executeAndWait(scenarioB, user);

        mockMvc.perform(patch("/api/notifications/read-all").with(user))
                .andExpect(status().isOk());

        String countResponse = mockMvc.perform(get("/api/notifications/unread-count").with(user))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(countResponse).get("count").asLong()).isEqualTo(0);
    }

    @Test
    void userA_cannotSeeOrModifyUserBsNotifications() throws Exception {
        RequestPostProcessor userA = asUser("notif-isolation-a-" + UUID.randomUUID());
        RequestPostProcessor userB = asUser("notif-isolation-b-" + UUID.randomUUID());

        String scenarioA = createScenarioWithSuccessfulStep("notif-iso-a", userA);
        executeAndWait(scenarioA, userA);

        JsonNode contentA = listNotifications(userA);
        assertThat(contentA.size()).isGreaterThanOrEqualTo(1);
        String userANotificationId = contentA.get(0).get("id").asText();

        // userB ne voit AUCUNE des notifications de userA dans sa propre liste.
        JsonNode contentB = listNotifications(userB);
        for (JsonNode n : contentB) {
            assertThat(n.get("id").asText()).isNotEqualTo(userANotificationId);
        }

        // userB ne peut ni lire, ni supprimer la notification de userA (404,
        // jamais un 403 qui confirmerait son existence a un autre utilisateur).
        mockMvc.perform(patch("/api/notifications/" + userANotificationId + "/read").with(userB))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/notifications/" + userANotificationId).with(userB))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_removesNotification_andSecondDeleteReturns404() throws Exception {
        RequestPostProcessor user = asUser("notif-user-delete-" + UUID.randomUUID());
        String scenarioId = createScenarioWithSuccessfulStep("notif-delete", user);
        executeAndWait(scenarioId, user);

        JsonNode content = listNotifications(user);
        String id = content.get(0).get("id").asText();

        mockMvc.perform(delete("/api/notifications/" + id).with(user)).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/notifications/" + id).with(user)).andExpect(status().isNotFound());
    }

    @Test
    void unreadFilter_returnsOnlyUnreadNotifications() throws Exception {
        RequestPostProcessor user = asUser("notif-user-filter-" + UUID.randomUUID());
        String scenarioId = createScenarioWithSuccessfulStep("notif-filter", user);
        executeAndWait(scenarioId, user);

        JsonNode all = listNotifications(user);
        String id = all.get(0).get("id").asText();
        mockMvc.perform(patch("/api/notifications/" + id + "/read").with(user)).andExpect(status().isOk());

        String unreadResponse = mockMvc.perform(get("/api/notifications").param("read", "false").with(user))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode unreadContent = objectMapper.readTree(unreadResponse).get("content");
        for (JsonNode n : unreadContent) {
            assertThat(n.get("id").asText()).isNotEqualTo(id);
            assertThat(n.get("read").asBoolean()).isFalse();
        }
    }
}
