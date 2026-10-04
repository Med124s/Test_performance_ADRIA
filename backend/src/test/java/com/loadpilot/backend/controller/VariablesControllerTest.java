package com.loadpilot.backend.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
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

/** Variables du scenario, distribution CSV, variables de chemin et parametres de requete (stockage, validation, test). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VariablesControllerTest {

    private static HttpServer server;
    private static String targetUrl;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockBean
    private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_ENGINEER = jwt().authorities(new SimpleGrantedAuthority("ROLE_PERFORMANCE_ENGINEER"));

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", ex -> {
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

    private ResultActions send(MockHttpServletRequestBuilder builder, Object body) throws Exception {
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        return mockMvc.perform(builder.with(AS_ENGINEER));
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void scenarioVariables_andRequestParams_areStoredValidated_andUsedByTheTest() throws Exception {
        String appId = json(send(post("/api/applications"), Map.of("name", "vars-app", "url", targetUrl))).get("id").asText();
        Map<String, Object> scenario = new HashMap<>(Map.of("applicationId", appId, "name", "Variables",
                "csvData", "transferId,amount\nTRF 9/A,150", "variables", "{\"currency\":\"MAD\",\"note\":\"Loyer & charges\"}",
                "csvDistribution", "PER_ITERATION"));
        String id = json(send(post("/api/scenarios"), scenario)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.variables").value("{\"currency\":\"MAD\",\"note\":\"Loyer & charges\"}"))
                .andExpect(jsonPath("$.csvDistribution").value("PER_ITERATION"))).get("id").asText();

        // Mise a jour sans variables : conservees ; nom invalide : refuse.
        send(put("/api/scenarios/" + id), Map.of("applicationId", appId, "name", "Variables 2", "csvData", "transferId,amount\nTRF 9/A,150"))
                .andExpect(jsonPath("$.variables").value(Matchers.containsString("currency")))
                .andExpect(jsonPath("$.csvDistribution").value("PER_ITERATION"));
        send(put("/api/scenarios/" + id), Map.of("applicationId", appId, "name", "x", "variables", "{\"mon montant\":\"1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("Variable du scénario « mon montant »")));

        Map<String, Object> step = new HashMap<>(Map.of("scenarioId", id, "name", "Lire", "method", "GET",
                "url", "/api/transfers/:transferId", "order", 1,
                "pathParams", "{\"transferId\":\"${transferId}\"}",
                "queryParams", "[{\"key\":\"currency\",\"value\":\"${currency}\",\"enabled\":true},"
                        + "{\"key\":\"note\",\"value\":\"${note}\",\"enabled\":true},{\"key\":\"debug\",\"value\":\"1\",\"enabled\":false}]"));
        send(post("/api/steps"), step)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pathParams").value("{\"transferId\":\"${transferId}\"}"));
        step.put("pathParams", "{\"transfer id\":\"x\"}");
        send(post("/api/steps"), step).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("Variable de chemin « transfer id »")));
        step.put("pathParams", null);
        step.put("queryParams", "[{\"value\":\"x\"}]");
        send(post("/api/steps"), step).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("chaque paramètre doit avoir un nom")));

        // « Tester » : URL finale construite par le backend, valeurs du CSV et du scenario encodees.
        send(post("/api/scenarios/" + id + "/test-run"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].status").value("PASSED"))
                .andExpect(jsonPath("$.steps[0].request.url")
                        .value(targetUrl + "/api/transfers/TRF%209%2FA?currency=MAD&note=Loyer%20%26%20charges"));
    }
}
