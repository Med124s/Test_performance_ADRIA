package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.config.EncryptApplicationSecretsChange;
import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.repository.ApplicationRepository;
import com.loadpilot.backend.security.SecretCipher;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
 * Correction P1 - secrets des applications testees chiffres en base : lecture directe en SQL
 * (jamais en clair), dechiffrement par le repository, aucune fuite par l'API ni les journaux,
 * comportement fonctionnel inchange (test de connexion avec jeton, monitoring avec secret
 * enregistre) et migration des donnees deja presentes en clair.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class SecretsEncryptionTest {

    private static final String TOKEN = "Tok-s3cret-L0adP1lot";
    private static final String MONITORING_SECRET = "Mon-s3cret-Pr0metheus";
    private static final String LOGIN_PASSWORD = "Pw-s3cret-Login";

    private static HttpServer target;
    private static String baseUrl;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private DataSource dataSource;
    @MockBean private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor ADMIN = jwt().jwt(j -> j.subject("secrets-admin").claim("preferred_username", "secrets-admin"))
            .authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));

    @BeforeAll
    static void startTarget() throws IOException {
        target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        // Verification du jeton de l'application : 401 sans le bon jeton, 200 avec.
        target.createContext("/me", ex -> {
            boolean ok = ("Bearer " + TOKEN).equals(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] body = (ok ? "{\"ok\":true}" : "{}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(ok ? 200 : 401, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        // Monitoring Prometheus protege par le secret du monitoring.
        target.createContext("/metrics", ex -> {
            boolean ok = ("Bearer " + MONITORING_SECRET).equals(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] body = (ok ? "process_cpu_seconds_total 1.5\nprocess_resident_memory_bytes 104857600\n" : "").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(ok ? 200 : 401, body.length == 0 ? -1 : body.length);
            if (body.length > 0) ex.getResponseBody().write(body);
            ex.close();
        });
        target.createContext("/", ex -> {
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        target.start();
        baseUrl = "http://localhost:" + target.getAddress().getPort();
    }

    @AfterAll
    static void stopTarget() {
        target.stop(0);
    }

    private MvcResult perform(MockHttpServletRequestBuilder builder, Object body) throws Exception {
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        return mockMvc.perform(builder.with(ADMIN)).andReturn();
    }

    private String createApp(Map<String, Object> auth, Map<String, Object> monitoring) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("name", "chiffre-" + UUID.randomUUID(), "url", baseUrl));
        if (auth != null) body.put("auth", auth);
        if (monitoring != null) body.put("monitoring", monitoring);
        MvcResult created = perform(post("/api/applications"), body);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        return objectMapper.readTree(created.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("id").asText();
    }

    private Map<String, Object> raw(String id) {
        return jdbc.queryForMap("SELECT auth_config, monitoring_secret FROM application WHERE id = ?", UUID.fromString(id));
    }

    @Test
    void storedEncrypted_decryptedByRepository_neverExposed_andConnectionAndMonitoringStillWork(CapturedOutput output) throws Exception {
        String id = createApp(Map.of("type", "BEARER_TOKEN", "token", TOKEN, "verificationPath", "/me"),
                Map.of("type", "PROMETHEUS", "url", baseUrl + "/metrics", "authType", "BEARER", "secret", MONITORING_SECRET));

        // 1. En base (lecture SQL directe) : chiffre, jamais en clair.
        Map<String, Object> row = raw(id);
        assertThat((String) row.get("auth_config")).startsWith(SecretCipher.PREFIX).doesNotContain(TOKEN);
        assertThat((String) row.get("monitoring_secret")).startsWith(SecretCipher.PREFIX).doesNotContain(MONITORING_SECRET);

        // 2. Repository (JPA) : dechiffre de facon transparente.
        Application entity = applicationRepository.findById(UUID.fromString(id)).orElseThrow();
        assertThat(entity.getAuthConfig()).contains(TOKEN);
        assertThat(entity.getMonitoringSecret()).isEqualTo(MONITORING_SECRET);

        // 3. API : jamais le secret, ni en clair ni chiffre.
        String api = perform(get("/api/applications/" + id), null).getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(api).doesNotContain(TOKEN).doesNotContain(MONITORING_SECRET).doesNotContain(SecretCipher.PREFIX);

        // 4. Comportement inchange : test de connexion avec le jeton dechiffre -> CONNECTED.
        JsonNode test = objectMapper.readTree(perform(post("/api/applications/" + id + "/test"), null)
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(test.get("status").asText()).isEqualTo("CONNECTED");

        // 5. Monitoring avec le secret ENREGISTRE (champ vide : reutilise depuis la base) -> lecture reussie.
        Map<String, Object> monitoring = new HashMap<>(Map.of("type", "PROMETHEUS", "url", baseUrl + "/metrics", "authType", "BEARER"));
        JsonNode mon = objectMapper.readTree(perform(post("/api/applications/monitoring/test"),
                Map.of("applicationId", id, "monitoring", monitoring)).getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(mon.get("success").asBoolean()).as(mon.toString()).isTrue();

        // 6. Journaux (niveau DEBUG en test) : aucun secret en clair.
        assertThat(output.getAll()).doesNotContain(TOKEN).doesNotContain(MONITORING_SECRET);
    }

    @Test
    void loginToken_bodyWithPassword_encrypted_andModificationKeepsSecretReadable() throws Exception {
        String id = createApp(Map.of("type", "LOGIN_TOKEN", "loginPath", "/login",
                "loginBody", "{\"username\":\"agent\",\"password\":\"" + LOGIN_PASSWORD + "\"}", "tokenJsonPath", "$.token"), null);

        assertThat((String) raw(id).get("auth_config")).startsWith(SecretCipher.PREFIX).doesNotContain(LOGIN_PASSWORD);
        assertThat(applicationRepository.findById(UUID.fromString(id)).orElseThrow().getAuthConfig()).contains(LOGIN_PASSWORD);
        assertThat(raw(id).get("monitoring_secret")).as("aucun monitoring : reste null").isNull();
    }

    @Test
    void migration_encryptsExistingPlainValues_keepsNullAndEmpty_andIsIdempotent(CapturedOutput output) throws Exception {
        String plainId = createApp(Map.of("type", "BEARER_TOKEN", "token", TOKEN, "verificationPath", "/me"), null);
        String emptyId = createApp(null, null);
        String plainAuth = "{\"token\":\"" + TOKEN + "\",\"verificationPath\":\"/me\"}";
        // Etat « avant cette version » : valeurs en clair ecrites directement en SQL (sans le convertisseur).
        jdbc.update("UPDATE application SET auth_config = ?, monitoring_secret = ? WHERE id = ?", plainAuth, MONITORING_SECRET,
                UUID.fromString(plainId));
        jdbc.update("UPDATE application SET auth_config = ?, monitoring_secret = NULL WHERE id = ?", "", UUID.fromString(emptyId));

        runMigration();

        Map<String, Object> row = raw(plainId);
        String auth = (String) row.get("auth_config");
        String secret = (String) row.get("monitoring_secret");
        assertThat(auth).startsWith(SecretCipher.PREFIX).doesNotContain(TOKEN);
        assertThat(secret).startsWith(SecretCipher.PREFIX).doesNotContain(MONITORING_SECRET);
        Application entity = applicationRepository.findById(UUID.fromString(plainId)).orElseThrow();
        assertThat(entity.getAuthConfig()).isEqualTo(plainAuth);
        assertThat(entity.getMonitoringSecret()).isEqualTo(MONITORING_SECRET);
        // Vide et null : inchanges.
        assertThat(raw(emptyId).get("auth_config")).isEqualTo("");
        assertThat(raw(emptyId).get("monitoring_secret")).isNull();

        // Rejouee : aucune valeur deja chiffree n'est modifiee.
        runMigration();
        assertThat(raw(plainId).get("auth_config")).isEqualTo(auth);
        assertThat(raw(plainId).get("monitoring_secret")).isEqualTo(secret);
        assertThat(output.getAll()).doesNotContain(TOKEN).doesNotContain(MONITORING_SECRET);
    }

    private void runMigration() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            new EncryptApplicationSecretsChange().execute(database);
            if (!connection.getAutoCommit()) connection.commit();
        }
    }
}
