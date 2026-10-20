package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * « Tester » une application avec un proxy propre : jamais un succes si le proxy est injoignable, et
 * un message qui designe LE PROXY (hote introuvable, port ferme) plutot que l'application ; succes
 * reel a travers un proxy qui fonctionne ; meme verification pour « Tester le monitoring ».
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApplicationProxyCheckTest {

    private static HttpServer target;
    private static ServerSocket proxy;
    private static final List<String> proxySaw = new CopyOnWriteArrayList<>();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor ADMIN = jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));

    @BeforeAll
    static void start() throws IOException {
        target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.createContext("/", ex -> {
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        target.start();
        // Vrai proxy minimal : repond 200 a toute requete relayee, et note ce qu'il recoit.
        proxy = new ServerSocket(0);
        Thread.ofVirtual().start(() -> {
            while (!proxy.isClosed()) {
                try (Socket s = proxy.accept()) {
                    String requestLine = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1)).readLine();
                    if (requestLine == null) continue; // verification de connexion, sans requete
                    proxySaw.add(requestLine);
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                } catch (IOException e) {
                    if (proxy.isClosed()) return;
                }
            }
        });
    }

    @AfterAll
    static void stop() throws IOException {
        target.stop(0);
        proxy.close();
    }

    private JsonNode call(MockHttpServletRequestBuilder builder, Object body) throws Exception {
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        return objectMapper.readTree(mockMvc.perform(builder.with(ADMIN)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** Cree une application (cible reelle et joignable) avec ce proxy, puis « Tester ». */
    private JsonNode testWithProxy(String host, int port) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("name", "proxy-test-" + UUID.randomUUID(),
                "url", "http://localhost:" + target.getAddress().getPort(),
                "network", Map.of("proxyMode", "CUSTOM", "proxyHost", host, "proxyPort", port)));
        String id = call(post("/api/applications"), body).get("id").asText();
        return call(post("/api/applications/" + id + "/test"), null);
    }

    private static int closedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    void unknownProxyHost_neverASuccess_andTheMessageNamesTheProxy() throws Exception {
        JsonNode result = testWithProxy("proxy.inexistant.invalid", 3128);
        assertThat(result.get("status").asText()).isEqualTo("ERROR");
        assertThat(result.get("message").asText())
                .isEqualTo("Proxy injoignable (proxy.inexistant.invalid:3128) : hôte du proxy introuvable (DNS).");
    }

    @Test
    void closedProxyPort_connectionRefused() throws Exception {
        int port = closedPort();
        JsonNode result = testWithProxy("localhost", port);
        assertThat(result.get("status").asText()).isEqualTo("ERROR");
        assertThat(result.get("message").asText())
                .isEqualTo("Proxy injoignable (localhost:" + port + ") : connexion refusée par le proxy (port fermé).");
    }

    @Test
    void workingProxy_realSuccess_throughTheProxy() throws Exception {
        proxySaw.clear();
        JsonNode result = testWithProxy("localhost", proxy.getLocalPort());
        assertThat(result.get("status").asText()).isEqualTo("CONNECTED");
        // La requete a bien ete relayee par le proxy (URL absolue de l'application), jamais envoyee en direct.
        assertThat(proxySaw).anyMatch(l -> l.startsWith("GET http://localhost:" + target.getAddress().getPort()));
    }

    @Test
    void invalidProxySettings_refusedBeforeAnyTest() throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("name", "proxy-invalide-" + UUID.randomUUID(), "url", "http://localhost:1",
                "network", Map.of("proxyMode", "CUSTOM", "proxyHost", "proxy", "proxyPort", 70000)));
        int status = mockMvc.perform(post("/api/applications").with(ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))).andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(400);
    }

    @Test
    void monitoringTest_withAnUnreachableProxy_namesTheProxy() throws Exception {
        int port = closedPort();
        JsonNode result = call(post("/api/applications/monitoring/test"), Map.of(
                "monitoring", Map.of("type", "PROMETHEUS", "url", "http://localhost:" + target.getAddress().getPort() + "/metrics", "authType", "NONE"),
                "network", Map.of("proxyMode", "CUSTOM", "proxyHost", "localhost", "proxyPort", port)));
        assertThat(result.get("success").asBoolean()).isFalse();
        assertThat(result.get("message").asText()).isEqualTo("Proxy injoignable (localhost:" + port + ") : connexion refusée par le proxy (port fermé).");
    }
}
