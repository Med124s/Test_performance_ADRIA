package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.ScenarioRepository;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** PUT /api/scenarios/{id}/full (transactionnel) et test d'une version non enregistree. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScenarioFullUpdateTest {

    private static HttpServer server;
    private static String targetUrl;
    private static final List<String> paths = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ExecutionRepository executionRepository;
    @Autowired
    private ScenarioRepository scenarioRepository;
    @MockBean
    private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_ENGINEER = jwt().authorities(new SimpleGrantedAuthority("ROLE_PERFORMANCE_ENGINEER"));
    private static final RequestPostProcessor AS_VIEWER = jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"));

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", ex -> {
            paths.add(ex.getRequestURI().getPath());
            byte[] bytes = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        targetUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private ResultActions send(MockHttpServletRequestBuilder builder, RequestPostProcessor as, Object body) throws Exception {
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        return mockMvc.perform(builder.with(as));
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private Map<String, Object> step(String scenarioId, String name, String url, String body) {
        Map<String, Object> s = new HashMap<>();
        s.put("scenarioId", scenarioId);
        s.put("name", name);
        s.put("method", "POST");
        s.put("url", url);
        s.put("body", body);
        s.put("order", 1);
        return s;
    }

    private Map<String, Object> payload(String id, Map<String, Object> step) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("step", step);
        return p;
    }

    @Test
    void fullUpdate_createsUpdatesDeletesAndReorders_inOneTransaction() throws Exception {
        String appId = json(send(post("/api/applications"), AS_ENGINEER, Map.of("name", "full-app", "url", targetUrl))).get("id").asText();
        Map<String, Object> scenario = new HashMap<>(Map.of("applicationId", appId, "name", "Complet", "iterations", 1));
        String id = json(send(post("/api/scenarios"), AS_ENGINEER, scenario)).get("id").asText();
        String a = json(send(post("/api/steps"), AS_ENGINEER, step(id, "A", "/a", null))).get("id").asText();
        String b = json(send(post("/api/steps"), AS_ENGINEER, step(id, "B", "/b", null))).get("id").asText();
        String c = json(send(post("/api/steps"), AS_ENGINEER, step(id, "C", "/c", null))).get("id").asText();

        scenario.put("name", "Complet v2");
        scenario.put("variables", "{\"currency\":\"MAD\"}");
        List<Object> steps = List.of(payload(c, step(id, "C renommée", "/c2", null)), payload(a, step(id, "A", "/a", null)),
                payload(null, step(id, "D nouvelle", "/d", "{\"x\": 1}")));
        send(put("/api/scenarios/" + id + "/full"), AS_VIEWER, Map.of("scenario", scenario, "steps", steps)).andExpect(status().isForbidden());
        JsonNode result = json(send(put("/api/scenarios/" + id + "/full"), AS_ENGINEER, Map.of("scenario", scenario, "steps", steps))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenario.name").value("Complet v2"))
                .andExpect(jsonPath("$.scenario.variables").value("{\"currency\":\"MAD\"}")));
        assertThat(result.get("steps")).extracting(s -> s.get("name").asText() + "@" + s.get("order").asInt())
                .containsExactly("C renommée@1", "A@2", "D nouvelle@3");
        assertThat(result.get("steps").get(0).get("id").asText()).isEqualTo(c);
        JsonNode stored = json(send(get("/api/steps?scenarioId=" + id), AS_VIEWER, null));
        assertThat(stored).extracting(s -> s.get("id").asText()).doesNotContain(b).hasSize(3);

        // Tout ou rien : une etape invalide (3e) -> 400 et AUCUNE modification (nom, etapes).
        scenario.put("name", "Ne doit pas etre enregistre");
        List<Object> invalid = new ArrayList<>(List.of(payload(c, step(id, "C", "/c", null)), payload(null, step(id, "E", "/e", "{\"x\": 1,}"))));
        send(put("/api/scenarios/" + id + "/full"), AS_ENGINEER, Map.of("scenario", scenario, "steps", invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("Étape 2 « E » : Corps JSON invalide")));
        send(get("/api/scenarios/" + id), AS_VIEWER, null).andExpect(jsonPath("$.name").value("Complet v2"));
        assertThat(json(send(get("/api/steps?scenarioId=" + id), AS_VIEWER, null))).hasSize(3);

        // Etape d'un autre scenario, liste vide : refus.
        send(put("/api/scenarios/" + id + "/full"), AS_ENGINEER, Map.of("scenario", scenario,
                "steps", List.of(payload(UUID.randomUUID().toString(), step(id, "X", "/x", null)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("n'appartient pas à ce scénario")));
        send(put("/api/scenarios/" + id + "/full"), AS_ENGINEER, Map.of("scenario", scenario, "steps", List.of())).andExpect(status().isBadRequest());

        // Test d'une version NON enregistree : etapes du corps, jusqu'a l'etape 2, rien n'est persiste.
        paths.clear();
        Map<String, Object> draft = Map.of("scenario", Map.of("applicationId", appId, "name", "brouillon"),
                "steps", List.of(payload(c, step(id, "Brouillon 1", "/brouillon-1", null)), payload(null, step(id, "Brouillon 2", "/brouillon-2", null)),
                        payload(null, step(id, "Brouillon 3", "/brouillon-3", null))));
        send(post("/api/scenarios/" + id + "/test-run?upToStep=2"), AS_ENGINEER, draft)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSteps").value(2))
                .andExpect(jsonPath("$.steps[1].name").value("Brouillon 2"))
                .andExpect(jsonPath("$.success").value(true));
        assertThat(paths).containsExactly("/brouillon-1", "/brouillon-2");
        assertThat(json(send(get("/api/steps?scenarioId=" + id), AS_VIEWER, null)).get(0).get("name").asText()).isEqualTo("C renommée");

        // Execution en cours : modification refusee (PUT simple et complet).
        executionRepository.saveAndFlush(Execution.builder().scenario(scenarioRepository.findById(UUID.fromString(id)).orElseThrow())
                .startedAt(Instant.now()).status(ExecutionStatus.RUNNING).totalSteps(3).successfulSteps(0).failedSteps(0).build());
        send(put("/api/scenarios/" + id + "/full"), AS_ENGINEER, Map.of("scenario", scenario, "steps", steps))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Une exécution de ce scénario est en cours : attendez sa fin ou annulez-la avant de le modifier."));
        send(put("/api/scenarios/" + id), AS_ENGINEER, scenario).andExpect(status().isConflict());
    }
}
