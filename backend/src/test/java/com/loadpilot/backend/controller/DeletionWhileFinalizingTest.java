package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.service.execution.RunningExecutionRegistry;
import com.loadpilot.backend.service.impl.DeletionServiceImpl;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Suppression juste apres une grosse execution : tant que l'execution enregistre encore ses
 * resultats / metriques, la suppression (scenario, groupe, application) est refusee avec un message
 * clair (409) - jamais une erreur 500 - puis acceptee une fois l'enregistrement termine.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeletionWhileFinalizingTest {

    private static HttpServer server;
    private static String targetUrl;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RunningExecutionRegistry registry;
    @MockBean
    private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_ADMIN = jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", ex -> {
            byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        targetUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private MvcResult perform(MockHttpServletRequestBuilder builder, Object body) throws Exception {
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        return mockMvc.perform(builder.with(AS_ADMIN)).andReturn();
    }

    private JsonNode json(MvcResult r) throws Exception {
        String s = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return s.isBlank() ? null : objectMapper.readTree(s);
    }

    @Test
    void deletionRefusedWithClearMessage_whileTheExecutionIsStillRecording_thenAccepted() throws Exception {
        String appId = json(perform(post("/api/applications"), Map.of("name", "finalisation-" + UUID.randomUUID(), "url", targetUrl)))
                .get("id").asText();
        String scenarioId = json(perform(post("/api/scenarios"), Map.of("applicationId", appId, "name", "Finalisation",
                "virtualUsers", 1, "rampUpSeconds", 0, "iterations", 1, "thinkTimeMs", 0))).get("id").asText();
        perform(post("/api/steps"), Map.of("scenarioId", scenarioId, "name", "Appel", "method", "GET", "url", "/x", "order", 1));
        String executionId = json(perform(post("/api/executions"), Map.of("scenarioId", scenarioId))).get("id").asText();
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && registry.get(UUID.fromString(executionId)) != null) Thread.sleep(50);
        assertThat(List.of("SUCCESS", "THRESHOLDS_EXCEEDED", "FAILED")).contains(json(perform(get("/api/executions/" + executionId + "/status"), null))
                .get("status").asText());

        // Simule une execution terminee en base mais dont l'enregistrement (metriques) n'est pas fini.
        UUID execUuid = UUID.fromString(executionId);
        registry.reserveCapacity(1);
        registry.registerReserved(execUuid, 1);
        try {
            MvcResult single = perform(delete("/api/scenarios/" + scenarioId), null);
            assertThat(single.getResponse().getStatus()).isEqualTo(409);
            assertThat(json(single).get("message").asText()).isEqualTo(DeletionServiceImpl.FINALIZING_MESSAGE);

            JsonNode bulk = json(perform(post("/api/scenarios/bulk-delete"), Map.of("ids", List.of(scenarioId))));
            assertThat(bulk.get("failed").asInt()).isEqualTo(1);
            assertThat(bulk.get("results").get(0).get("message").asText()).contains("réessayez dans quelques secondes");

            MvcResult app = perform(delete("/api/applications/" + appId), null);
            assertThat(app.getResponse().getStatus()).isEqualTo(409);
            assertThat(json(app).get("message").asText()).isEqualTo(DeletionServiceImpl.FINALIZING_MESSAGE);
        } finally {
            registry.unregister(execUuid);
        }

        // Enregistrement termine : la suppression passe.
        assertThat(perform(delete("/api/scenarios/" + scenarioId), null).getResponse().getStatus()).isEqualTo(204);
        assertThat(perform(delete("/api/applications/" + appId), null).getResponse().getStatus()).isEqualTo(204);
    }
}
