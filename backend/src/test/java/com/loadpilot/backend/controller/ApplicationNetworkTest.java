package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.repository.ApplicationRepository;
import com.loadpilot.backend.repository.AuditLogRepository;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Proxy par application, de bout en bout : enregistrement (proxy global par defaut, aucun, propre a
 * l'application), mot de passe chiffre en base et jamais renvoye (vide = conserve), validation
 * identique aux scripts, journal d'audit sans mot de passe, « Monitoring sans proxy », et « Tester le
 * monitoring » qui reutilise le mot de passe enregistre a travers le proxy de l'application.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApplicationNetworkTest {

    private static final String PASSWORD = "Pr0xy-s3cret";
    private static final String EXPECTED = "Basic " + Base64.getEncoder().encodeToString(("agent:" + PASSWORD).getBytes(StandardCharsets.UTF_8));

    private static ServerSocket proxy;
    private static final List<String> proxySaw = new CopyOnWriteArrayList<>();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @MockBean private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor ADMIN = jwt().jwt(j -> j.subject("proxy-admin").claim("preferred_username", "proxy-admin"))
            .authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));

    /** Faux proxy HTTP : 407 sans le bon identifiant, sinon repond lui-meme (metriques Prometheus). */
    @BeforeAll
    static void startProxy() throws IOException {
        proxy = new ServerSocket(0);
        Thread.ofVirtual().start(() -> {
            while (!proxy.isClosed()) {
                try (Socket s = proxy.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
                    String requestLine = in.readLine();
                    if (requestLine == null) continue; // simple verification de connexion au proxy, sans requete
                    String auth = null;
                    for (String line; (line = in.readLine()) != null && !line.isEmpty(); ) {
                        if (line.toLowerCase().startsWith("proxy-authorization:")) auth = line.substring(20).trim();
                    }
                    boolean ok = EXPECTED.equals(auth);
                    proxySaw.add(requestLine + " | " + (ok ? "identifiant correct" : "sans identifiant correct"));
                    String body = ok ? "process_cpu_seconds_total 1.5\nprocess_resident_memory_bytes 104857600\n" : "";
                    OutputStream out = s.getOutputStream();
                    out.write(("HTTP/1.1 " + (ok ? "200 OK" : "407 Proxy Authentication Required") + "\r\nContent-Length: " + body.length()
                            + "\r\nConnection: close\r\n\r\n" + body).getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                } catch (IOException e) {
                    if (proxy.isClosed()) return;
                }
            }
        });
    }

    @AfterAll
    static void stopProxy() throws IOException {
        proxy.close();
    }

    private MvcResult perform(MockHttpServletRequestBuilder builder, Object body) throws Exception {
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        return mockMvc.perform(builder.with(ADMIN)).andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> customProxy(String password) {
        Map<String, Object> network = new HashMap<>(Map.of("proxyMode", "CUSTOM", "proxyHost", "localhost", "proxyPort", proxy.getLocalPort(),
                "proxyUsername", "agent"));
        if (password != null) network.put("proxyPassword", password);
        return network;
    }

    private Map<String, Object> app(Map<String, Object> network) {
        Map<String, Object> body = new HashMap<>(Map.of("name", "proxy-" + UUID.randomUUID(), "url", "http://app.test"));
        if (network != null) body.put("network", network);
        return body;
    }

    @Test
    void newApplication_byDefault_noProxy_withOrWithoutANetworkSection() throws Exception {
        JsonNode created = json(perform(post("/api/applications"), app(null)));
        assertThat(created.get("network").get("proxyMode").asText()).isEqualTo("NONE");
        assertThat(created.get("network").get("proxyPasswordConfigured").asBoolean()).isFalse();
        assertThat(created.get("monitoring").get("withoutProxy").asBoolean()).isFalse();
        String id = created.get("id").asText();
        assertThat(jdbc.queryForObject("SELECT proxy_mode FROM application WHERE id = ?", String.class, UUID.fromString(id)))
                .as("enregistre explicitement").isEqualTo("NONE");

        // Section reseau sans mode (ex. autorite de certification seule) : aucun proxy aussi.
        JsonNode withSection = json(perform(post("/api/applications"), app(new HashMap<>(Map.of("proxyHost", "")))));
        assertThat(withSection.get("network").get("proxyMode").asText()).isEqualTo("NONE");

        // Choix explicite respecte.
        JsonNode server = json(perform(post("/api/applications"), app(Map.of("proxyMode", "INHERIT"))));
        assertThat(server.get("network").get("proxyMode").asText()).isEqualTo("INHERIT");
    }

    @Test
    void monitoringWithoutProxy_underNoProxy_isKeptAsIs_andAppliesAgainWithAProxy() throws Exception {
        // Choix retenu : ignore sans proxy (lecture directe de toute facon), jamais remis a false -
        // comme le reglage, deja garde quand le monitoring est desactive (MonitoringSupport).
        Map<String, Object> body = app(Map.of("proxyMode", "NONE"));
        body.put("monitoring", new HashMap<>(Map.of("type", "NONE", "withoutProxy", true)));
        JsonNode created = json(perform(post("/api/applications"), body));
        assertThat(created.get("network").get("proxyMode").asText()).isEqualTo("NONE");
        assertThat(created.get("monitoring").get("withoutProxy").asBoolean()).isTrue();
        String id = created.get("id").asText();

        // Retour a un proxy : la case reprend effet telle que l'utilisateur l'avait laissee.
        body.put("network", Map.of("proxyMode", "INHERIT"));
        JsonNode updated = json(perform(put("/api/applications/" + id), body));
        assertThat(updated.get("network").get("proxyMode").asText()).isEqualTo("INHERIT");
        assertThat(updated.get("monitoring").get("withoutProxy").asBoolean()).isTrue();
    }

    @Test
    void existingApplications_keepTheirMode_theNewDefaultIsOnlyForCreations() throws Exception {
        // Application creee avant ce changement : mode vide en base, toujours lu comme « Proxy du serveur ».
        String legacy = json(perform(post("/api/applications"), app(null))).get("id").asText();
        jdbc.update("UPDATE application SET proxy_mode = NULL WHERE id = ?", UUID.fromString(legacy));
        assertThat(json(perform(get("/api/applications/" + legacy), null)).get("network").get("proxyMode").asText()).isEqualTo("INHERIT");

        // Mise a jour sans section reseau : mode inchange (vide -> toujours le proxy du serveur).
        Map<String, Object> update = new HashMap<>(Map.of("name", "legacy-" + UUID.randomUUID(), "url", "http://app.test"));
        assertThat(json(perform(put("/api/applications/" + legacy), update)).get("network").get("proxyMode").asText()).isEqualTo("INHERIT");
        assertThat(jdbc.queryForObject("SELECT proxy_mode FROM application WHERE id = ?", String.class, UUID.fromString(legacy))).isNull();

        // Proxy du serveur ou de l'application choisis explicitement : conserves par une mise a jour sans section reseau.
        String server = json(perform(post("/api/applications"), app(Map.of("proxyMode", "INHERIT")))).get("id").asText();
        update.put("name", "server-" + UUID.randomUUID());
        assertThat(json(perform(put("/api/applications/" + server), update)).get("network").get("proxyMode").asText()).isEqualTo("INHERIT");
        String custom = json(perform(post("/api/applications"), app(customProxy(PASSWORD)))).get("id").asText();
        update.put("name", "custom-" + UUID.randomUUID());
        assertThat(json(perform(put("/api/applications/" + custom), update)).get("network").get("proxyMode").asText()).isEqualTo("CUSTOM");
    }

    @Test
    void customProxy_passwordEncryptedInDatabase_neverReturned_keptWhenBlank_clearedWhenLeft() throws Exception {
        MvcResult result = perform(post("/api/applications"), app(customProxy(PASSWORD)));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String raw = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(raw).doesNotContain(PASSWORD);
        JsonNode network = json(result).get("network");
        assertThat(network.get("proxyMode").asText()).isEqualTo("CUSTOM");
        assertThat(network.get("proxyHost").asText()).isEqualTo("localhost");
        assertThat(network.get("proxyUsername").asText()).isEqualTo("agent");
        assertThat(network.get("proxyPasswordConfigured").asBoolean()).isTrue();
        assertThat(network.has("proxyPassword")).isFalse();
        String id = json(result).get("id").asText();

        // Chiffre en base (lu en SQL brut), dechiffre par le repository.
        String stored = jdbc.queryForObject("SELECT proxy_password FROM application WHERE id = ?", String.class, UUID.fromString(id));
        assertThat(stored).startsWith("enc:v1:").doesNotContain(PASSWORD);
        assertThat(applicationRepository.findById(UUID.fromString(id)).orElseThrow().getProxyPassword()).isEqualTo(PASSWORD);

        // Mise a jour sans mot de passe : conserve ; lecture : toujours sans mot de passe.
        perform(put("/api/applications/" + id), app(customProxy(null)));
        assertThat(applicationRepository.findById(UUID.fromString(id)).orElseThrow().getProxyPassword()).isEqualTo(PASSWORD);
        assertThat(perform(get("/api/applications/" + id), null).getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain(PASSWORD);

        // Journal d'audit : hote, port, identifiant, jamais le mot de passe.
        assertThat(auditLogRepository.findAll().stream().filter(l -> id.equals(l.getTargetId())).map(l -> String.valueOf(l.getChanges()))
                .reduce("", String::concat)).contains("Hôte du proxy").doesNotContain(PASSWORD);

        // Plus de proxy propre : hote, identifiant et mot de passe effaces.
        JsonNode none = json(perform(put("/api/applications/" + id), app(Map.of("proxyMode", "NONE")))).get("network");
        assertThat(none.get("proxyMode").asText()).isEqualTo("NONE");
        assertThat(none.get("proxyHost").isNull()).isTrue();
        assertThat(none.get("proxyPasswordConfigured").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT proxy_password FROM application WHERE id = ?", String.class, UUID.fromString(id))).isNull();
    }

    @Test
    void validation_sameRulesAsTheStartScripts() throws Exception {
        for (Map<String, Object> invalid : List.of(
                Map.<String, Object>of("proxyMode", "CUSTOM", "proxyPort", 8080),
                Map.<String, Object>of("proxyMode", "CUSTOM", "proxyHost", "http://proxy", "proxyPort", 8080),
                Map.<String, Object>of("proxyMode", "CUSTOM", "proxyHost", "proxy", "proxyPort", 70000),
                Map.<String, Object>of("proxyMode", "CUSTOM", "proxyHost", "proxy", "proxyPort", 8080, "proxyUsername", "a:b"))) {
            MvcResult refused = perform(post("/api/applications"), app(invalid));
            assertThat(refused.getResponse().getStatus()).as(invalid.toString()).isEqualTo(400);
        }
    }

    @Test
    void monitoringTest_reusesTheSavedPassword_throughTheApplicationProxy_unlessWithoutProxy() throws Exception {
        Map<String, Object> monitoring = Map.of("type", "PROMETHEUS", "url", "http://app.test/metrics", "authType", "NONE");
        Map<String, Object> body = app(customProxy(PASSWORD));
        body.put("monitoring", monitoring);
        String id = json(perform(post("/api/applications"), body)).get("id").asText();
        proxySaw.clear();

        // Formulaire non enregistre : proxy saisi sans mot de passe -> mot de passe enregistre reutilise.
        JsonNode tested = json(perform(post("/api/applications/monitoring/test"),
                Map.of("applicationId", id, "monitoring", monitoring, "network", customProxy(null))));
        assertThat(tested.get("success").asBoolean()).as(tested.toString()).isTrue();
        assertThat(proxySaw).isNotEmpty().allMatch(l -> l.startsWith("GET http://app.test/metrics") && l.endsWith("identifiant correct"));

        // « Monitoring sans proxy » : lecture en direct, le proxy n'est pas utilise (app.test injoignable en direct).
        proxySaw.clear();
        Map<String, Object> direct = new HashMap<>(monitoring);
        direct.put("withoutProxy", true);
        JsonNode directTest = json(perform(post("/api/applications/monitoring/test"), Map.of("applicationId", id, "monitoring", direct)));
        assertThat(directTest.get("success").asBoolean()).isFalse();
        assertThat(proxySaw).isEmpty();

        // Reglage enregistre et renvoye.
        Map<String, Object> saved = app(null);
        saved.put("monitoring", direct);
        assertThat(json(perform(put("/api/applications/" + id), saved)).get("monitoring").get("withoutProxy").asBoolean()).isTrue();
    }
}
