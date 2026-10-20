package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Suivi en direct de l'injecteur : CPU, memoire et threads virtuels de LoadPilot dans
 * GET /api/executions/{id}/status pendant le test, et avertissement de saturation (seuil abaisse a
 * 0 % ici pour le provoquer a coup sur) ; plus rien une fois le test termine.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "app.injector.cpu-warning-percent=0")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExecutionInjectorStatusTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor ENGINEER = jwt().jwt(j -> j.subject("injector-engineer"))
            .authorities(new SimpleGrantedAuthority("ROLE_PERFORMANCE_ENGINEER"));

    private JsonNode call(MockHttpServletRequestBuilder builder, Object body) throws Exception {
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        String json = mockMvc.perform(builder.with(ENGINEER)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.isBlank() ? null : objectMapper.readTree(json);
    }

    @Test
    void liveStatus_showsTheInjector_andWarnsWhenItsCpuReachesTheThreshold() throws Exception {
        HttpServer target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.createContext("/", ex -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        target.start();
        try {
            String appId = call(post("/api/applications"), Map.of("name", "injecteur-" + UUID.randomUUID(),
                    "url", "http://localhost:" + target.getAddress().getPort())).get("id").asText();
            String scenarioId = call(post("/api/scenarios"), Map.of("applicationId", appId, "name", "Injecteur", "virtualUsers", 3,
                    "rampUpSeconds", 0, "thinkTimeMs", 0, "durationSeconds", 4)).get("id").asText();
            call(post("/api/steps"), Map.of("scenarioId", scenarioId, "name", "Appel", "method", "GET", "url", "/ok", "order", 1));
            String executionId = call(post("/api/executions"), Map.of("scenarioId", scenarioId)).get("id").asText();

            JsonNode injector = null;
            long deadline = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < deadline) {
                JsonNode status = call(get("/api/executions/" + executionId + "/status"), null);
                if (status.hasNonNull("injector") && status.get("live").get("activeVirtualUsers").asInt() == 3) {
                    injector = status.get("injector");
                    break;
                }
                Thread.sleep(100);
            }
            assertThat(injector).as("injecteur dans le suivi en direct").isNotNull();
            assertThat(injector.get("activeVirtualThreads").asInt()).as("un thread virtuel par utilisateur actif").isEqualTo(3);
            assertThat(injector.get("heapMaxMb").asLong()).isPositive();
            assertThat(injector.get("platformThreads").asInt()).isPositive();
            assertThat(injector.get("cpuWarningPercent").asDouble()).isZero();
            if (!injector.get("cpuPercent").isNull()) {
                assertThat(injector.get("overloaded").asBoolean()).isTrue();
                assertThat(injector.get("warning").asText()).startsWith("Injecteur saturé : le CPU de LoadPilot a atteint");
            }

            // Test termine : plus de mesure de l'injecteur.
            long end = System.currentTimeMillis() + 15_000;
            JsonNode status = null;
            while (System.currentTimeMillis() < end) {
                status = call(get("/api/executions/" + executionId + "/status"), null);
                if (!List.of("QUEUED", "RUNNING", "PAUSED").contains(status.get("status").asText())) break;
                Thread.sleep(100);
            }
            assertThat(status.get("injector").isNull()).isTrue();
        } finally {
            target.stop(0);
        }
    }
}
