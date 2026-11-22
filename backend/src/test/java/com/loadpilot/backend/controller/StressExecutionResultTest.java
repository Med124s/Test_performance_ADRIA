package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.dto.request.ApplicationRequest;
import com.loadpilot.backend.dto.request.ExecutionRequest;
import com.loadpilot.backend.dto.request.StepRequest;
import com.loadpilot.backend.enums.HttpMethod;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Deque;
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
 * Test de stress de bout en bout par l'API (environ une minute) : la cible ralentit au-dela de 6 req/s, le verdict est
 * « Seuils dépassés » et le point de rupture est enregistre avec l'execution.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StressExecutionResultTest {

    private static final int CAPACITY_RPS = 6;
    private static final Deque<Long> arrivals = new ArrayDeque<>();
    private static HttpServer target;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockBean
    private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_SUPER_ADMIN =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));

    @BeforeAll
    static void startTarget() throws IOException {
        target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.createContext("/", ex -> {
            boolean overloaded;
            synchronized (arrivals) {
                long now = System.nanoTime();
                arrivals.addLast(now);
                while (!arrivals.isEmpty() && arrivals.peekFirst() < now - 1_000_000_000L) arrivals.removeFirst();
                overloaded = arrivals.size() > CAPACITY_RPS;
            }
            if (overloaded) {
                try {
                    Thread.sleep(800);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        target.setExecutor(Executors.newCachedThreadPool());
        target.start();
    }

    @AfterAll
    static void stopTarget() {
        target.stop(0);
    }

    private String postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url).with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(body instanceof String s ? s : objectMapper.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString();
    }

    private JsonNode execution(String id) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/api/executions/" + id).with(AS_SUPER_ADMIN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void rupture_isAThresholdsExceededVerdict_withTheBreakpointStoredOnTheExecution() throws Exception {
        String baseUrl = "http://localhost:" + target.getAddress().getPort();
        String appId = objectMapper.readTree(postJson("/api/applications", new ApplicationRequest("stress-result-app", "d", baseUrl)))
                .get("id").asText();
        String scenarioId = objectMapper.readTree(postJson("/api/scenarios", objectMapper.createObjectNode()
                .put("applicationId", appId).put("name", "stress-resultat").put("loadMode", "STRESS").toString())).get("id").asText();
        postJson("/api/steps", new StepRequest(UUID.fromString(scenarioId), "etape", HttpMethod.GET, "/x", null, null, 1, 200,
                null, null, null, null, null, null));

        String executionId = objectMapper.readTree(postJson("/api/executions", new ExecutionRequest(UUID.fromString(scenarioId))))
                .get("id").asText();
        JsonNode e = execution(executionId);
        long deadline = System.currentTimeMillis() + 150_000;
        while (System.currentTimeMillis() < deadline && !e.get("status").asText().matches("SUCCESS|THRESHOLDS_EXCEEDED|FAILED|CANCELLED")) {
            Thread.sleep(500);
            e = execution(executionId);
        }
        // Le statut final est ecrit avant le resultat du stress dans la meme transaction : relu une fois termine.
        Thread.sleep(500);
        e = execution(executionId);

        assertThat(e.get("status").asText()).isEqualTo("THRESHOLDS_EXCEEDED");
        assertThat(e.get("loadMode").asText()).isEqualTo("STRESS");
        assertThat(e.get("errorMessage").asText()).startsWith("Point de rupture vers ");
        JsonNode stress = e.get("stress");
        assertThat(stress.get("outcome").asText()).isEqualTo("BREAKPOINT");
        // Reponses 100 fois plus lentes : le P95 explose et, chaque VU servant moins de requetes, le debit decroche ;
        // l'un ou l'autre confirme la rupture en premier.
        java.util.List<String> criteria = new java.util.ArrayList<>();
        stress.get("criteria").forEach(c -> criteria.add(c.asText()));
        assertThat(criteria).isNotEmpty().isSubsetOf("RESPONSE_TIME", "THROUGHPUT");
        assertThat(stress.get("onsetSeconds").asInt()).isBetween(40, 55);
        assertThat(stress.get("detectedSeconds").asInt()).isGreaterThan(stress.get("onsetSeconds").asInt());
        assertThat(stress.get("offeredRps").asDouble()).isBetween(3.0, 18.0);
        assertThat(stress.get("baselineP95Ms").asLong()).isLessThan(800);
        if (criteria.contains("RESPONSE_TIME")) assertThat(stress.get("p95Ms").asLong()).isGreaterThanOrEqualTo(800);
        assertThat(stress.get("message").asText()).isEqualTo(e.get("errorMessage").asText());
        // Utilisateurs virtuels de l'execution : pic de la montee, et non la limite reservee au lancement.
        assertThat(e.get("virtualUsers").asInt()).isEqualTo(stress.get("peakVirtualUsers").asInt()).isLessThan(200);

        JsonNode history = objectMapper.readTree(mockMvc.perform(get("/api/executions/history").param("scenarioId", scenarioId)
                .with(AS_SUPER_ADMIN)).andReturn().getResponse().getContentAsString());
        assertThat(history.toString()).contains("Point de rupture : ");
    }
}
