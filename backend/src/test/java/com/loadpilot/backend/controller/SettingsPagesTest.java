package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.entity.AuditLog;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.exception.KeycloakAdminException;
import com.loadpilot.backend.repository.AuditLogRepository;
import com.loadpilot.backend.service.AppSettingsService;
import com.loadpilot.backend.service.impl.ResultsRetentionJob;
import com.loadpilot.backend.service.keycloak.KeycloakAdminClient;
import com.loadpilot.backend.service.keycloak.KeycloakLogin;
import com.loadpilot.backend.service.keycloak.KeycloakSession;
import com.loadpilot.backend.service.keycloak.KeycloakSmtpInfo;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
 * Page Parametres : profil (role applicatif, apparence), securite (sessions, deconnexion de tous
 * les appareils, changement de mot de passe journalise), seuils par defaut (lecture pour tous,
 * modification Super administrateur, journal avant -> apres, utilises a la creation des
 * scenarios), stockage et purges, integrations (aucun secret), notifications par e-mail.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SettingsPagesTest {

    private static HttpServer server;
    private static String baseUrl;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private AppSettingsService settings;
    @Autowired private com.loadpilot.backend.service.execution.RunningExecutionRegistry registry;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired private com.loadpilot.backend.repository.AppSettingRepository settingsRepository;
    @Autowired private ResultsRetentionJob resultsRetentionJob;
    @Autowired private com.loadpilot.backend.service.audit.AuditRetentionJob auditRetentionJob;
    @MockBean private JwtDecoder jwtDecoder;
    @MockBean private KeycloakAdminClient keycloak;

    private static final String SECRET = "Bearer-S3cr3t-Value-42";

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/metrics", ex -> {
            byte[] body = """
                    # TYPE process_cpu_seconds_total counter
                    process_cpu_seconds_total 12.5
                    # TYPE process_resident_memory_bytes gauge
                    process_resident_memory_bytes 104857600
                    """.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/", ex -> {
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @AfterEach
    void resetThresholds() {
        settings.set(AppSettingsService.DEFAULT_MAX_ERROR_RATE, "1.0", "test");
        settings.set(AppSettingsService.DEFAULT_MAX_P95_MS, "0", "test");
        settings.set(AppSettingsService.REGRESSION_PERCENT, "20.0", "test");
        for (String key : List.of("limits.virtual-users-per-test", "limits.virtual-users-total", "limits.concurrent-tests", "limits.max-duration-seconds",
                "limits.max-iterations", "limits.max-target-rps",
                AppSettingsService.DEFAULT_ERROR_BRAKE_ENABLED, AppSettingsService.DEFAULT_ERROR_BRAKE_RATE,
                AppSettingsService.DEFAULT_ERROR_BRAKE_WINDOW, AppSettingsService.DEFAULT_THROTTLE_ON_429,
                AppSettingsService.DEFAULT_THROTTLE_DELAY, AppSettingsService.DEFAULT_THROTTLE_RETRIES,
                AppSettingsService.DEFAULT_REQUEST_DETAIL_MODE)) {
            if (settingsRepository.existsById(key)) settingsRepository.deleteById(key);
        }
        settings.clearCache();
    }

    private static RequestPostProcessor user(String subject, String role) {
        return jwt().jwt(j -> j.subject(subject).claim("preferred_username", subject).claim("email", subject + "@loadpilot.local")
                .claim("sid", "session-courante")).authorities(new SimpleGrantedAuthority(role));
    }

    private static final RequestPostProcessor ADMIN = user("admin-parametres", "ROLE_SUPER_ADMIN");
    private static final RequestPostProcessor ENGINEER = user("engineer-parametres", "ROLE_PERFORMANCE_ENGINEER");
    private static final RequestPostProcessor VIEWER = user("viewer-parametres", "ROLE_VIEWER");

    private MvcResult perform(MockHttpServletRequestBuilder builder, Object body, RequestPostProcessor as) throws Exception {
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        return mockMvc.perform(builder.with(as)).andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        String s = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return s.isBlank() ? null : objectMapper.readTree(s);
    }

    /** Entrees du journal pour cette cible, la plus recente d'abord (plusieurs tests modifient la meme cible). */
    private List<AuditLog> audits(AuditAction action, String targetId) {
        return auditLogRepository.findAll().stream().filter(a -> a.getAction() == action && targetId.equals(a.getTargetId()))
                .sorted(java.util.Comparator.comparing(AuditLog::getDate).reversed()).toList();
    }

    // ---------------------------------------------------------------- profil

    @Test
    void profile_onlyApplicationRoleInFrench_andThemeRemembered() throws Exception {
        RequestPostProcessor withTechnicalRoles = jwt().jwt(j -> j.subject("profil-roles").claim("preferred_username", "profil-roles"))
                .authorities(new SimpleGrantedAuthority("ROLE_PERFORMANCE_ENGINEER"), new SimpleGrantedAuthority("ROLE_offline_access"),
                        new SimpleGrantedAuthority("ROLE_uma_authorization"), new SimpleGrantedAuthority("ROLE_default-roles-loadpilot"));
        JsonNode profile = json(perform(get("/api/profile"), null, withTechnicalRoles));
        assertThat(profile.get("appRole").asText()).isEqualTo("PERFORMANCE_ENGINEER");
        assertThat(profile.get("appRoleLabel").asText()).isEqualTo("Ingénieur performance");
        assertThat(profile.get("theme").isNull()).isTrue();
        assertThat(json(perform(get("/api/profile"), null, ADMIN)).get("appRoleLabel").asText()).isEqualTo("Super administrateur");
        assertThat(json(perform(get("/api/profile"), null, VIEWER)).get("appRoleLabel").asText()).isEqualTo("Observateur");

        assertThat(json(perform(patch("/api/profile"), Map.of("theme", "SYSTEM"), withTechnicalRoles)).get("theme").asText()).isEqualTo("SYSTEM");
        assertThat(json(perform(get("/api/profile"), null, withTechnicalRoles)).get("theme").asText()).isEqualTo("SYSTEM");
        assertThat(perform(patch("/api/profile"), Map.of("theme", "BLUE"), withTechnicalRoles).getResponse().getStatus()).isEqualTo(400);
        // Le fuseau horaire seul reste modifiable, et l'apparence est conservee.
        JsonNode tz = json(perform(patch("/api/profile"), Map.of("timezone", "UTC"), withTechnicalRoles));
        assertThat(tz.get("timezone").asText()).isEqualTo("UTC");
        assertThat(tz.get("theme").asText()).isEqualTo("SYSTEM");
        assertThat(perform(patch("/api/profile"), Map.of(), withTechnicalRoles).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void security_sessionsFromAccountApi_orAdminFallback_logoutEverywhere_andPasswordChange() throws Exception {
        when(keycloak.recentLogins(eq("admin-parametres"), anyInt()))
                .thenReturn(List.of(new KeycloakLogin(Instant.parse("2026-10-08T07:00:00Z"), "127.0.0.1")));
        when(keycloak.accountSessions(any())).thenReturn(List.of(
                new KeycloakSession("autre", "10.0.0.5", Instant.parse("2026-10-07T08:00:00Z"), Instant.parse("2026-10-07T09:00:00Z"),
                        List.of("LoadPilot"), "Firefox/131.0", "Linux", null, false, false),
                new KeycloakSession("session-courante", "127.0.0.1", Instant.parse("2026-10-08T07:00:00Z"),
                        Instant.parse("2026-10-08T07:30:00Z"), List.of("LoadPilot"), "Chrome/129.0.0", "Windows 10", null, false, true)));
        JsonNode security = json(perform(get("/api/profile/security"), null, ADMIN));
        assertThat(security.get("lastLoginIp").asText()).isEqualTo("localhost");
        assertThat(security.get("devicesKnown").asBoolean()).isTrue();
        assertThat(security.get("sessions").get(0).get("current").asBoolean()).isTrue();
        assertThat(security.get("sessions").get(0).get("device").asText()).isEqualTo("Chrome sur Windows 10");
        assertThat(security.get("sessions").get(1).get("device").asText()).isEqualTo("Firefox sur Linux");

        // API « compte » indisponible : sessions de l'API d'administration, session courante reconnue par "sid".
        when(keycloak.accountSessions(any())).thenThrow(new KeycloakAdminException("indisponible"));
        when(keycloak.userSessions("admin-parametres")).thenReturn(List.of(
                new KeycloakSession("session-courante", "127.0.0.1", Instant.now(), Instant.now(), List.of(), null, null, null, false, false)));
        JsonNode fallback = json(perform(get("/api/profile/security"), null, ADMIN));
        assertThat(fallback.get("devicesKnown").asBoolean()).isFalse();
        assertThat(fallback.get("sessions").get(0).get("current").asBoolean()).isTrue();
        assertThat(fallback.toString()).doesNotContain("Bearer");

        assertThat(mockMvc.perform(post("/api/profile/logout-everywhere")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(perform(post("/api/profile/logout-everywhere"), null, ADMIN).getResponse().getStatus()).isEqualTo(204);
        verify(keycloak).logoutUser("admin-parametres");
        assertThat(audits(AuditAction.LOGOUT, "admin-parametres")).anyMatch(a -> a.getModule() == AuditModule.SECURITY
                && a.getDescription().equals("Déconnexion de tous les appareils, demandée depuis Paramètres"));

        // Retour de la page « changer mon mot de passe » : evenement importe, jamais fabrique.
        assertThat(json(perform(post("/api/profile/password-changed"), null, ADMIN)).get("recorded").asBoolean()).isFalse();
        when(keycloak.securityEvents(anyInt(), anyInt())).thenReturn(List.of(new com.loadpilot.backend.service.keycloak.KeycloakSecurityEvent(
                "evt-" + UUID.randomUUID(), "UPDATE_PASSWORD", System.currentTimeMillis(), "admin-parametres", null, "127.0.0.1", null)));
        when(keycloak.listUsers()).thenReturn(List.of());
        assertThat(json(perform(post("/api/profile/password-changed"), null, ADMIN)).get("recorded").asBoolean()).isTrue();
        assertThat(auditLogRepository.findAll().stream().filter(a -> a.getAction() == AuditAction.PASSWORD_CHANGED
                && "admin-parametres".equals(a.getUserId())).toList()).hasSize(1)
                .allMatch(a -> !a.getDescription().toLowerCase().contains("password=") && a.getDescription().startsWith("Mot de passe"));
    }

    // ---------------------------------------------------------------- seuils par defaut

    @Test
    void thresholds_readByAll_updateByAdminOnly_audited_andAppliedToNewScenarios() throws Exception {
        JsonNode defaults = json(perform(get("/api/settings/thresholds"), null, VIEWER));
        assertThat(defaults.get("maxErrorRatePercent").asDouble()).isEqualTo(1.0);
        assertThat(defaults.get("maxP95Ms").asLong()).isZero();
        assertThat(defaults.get("regressionPercent").asDouble()).isEqualTo(20.0);

        Map<String, Object> body = Map.of("maxErrorRatePercent", 2.5, "maxP95Ms", 800, "regressionPercent", 15);
        assertThat(perform(put("/api/settings/thresholds"), body, ENGINEER).getResponse().getStatus()).isEqualTo(403);
        assertThat(perform(put("/api/settings/thresholds"), Map.of("maxErrorRatePercent", 150, "maxP95Ms", 0, "regressionPercent", 20), ADMIN)
                .getResponse().getStatus()).isEqualTo(400);
        JsonNode updated = json(perform(put("/api/settings/thresholds"), body, ADMIN));
        assertThat(updated.get("regressionPercent").asDouble()).isEqualTo(15.0);
        assertThat(settings.regressionPercent()).isEqualTo(15.0);
        AuditLog entry = audits(AuditAction.UPDATE, "thresholds").get(0);
        assertThat(entry.getChanges()).contains("Seuil de régression").contains("20 %").contains("15 %")
                .contains("P95 maximal proposé").contains("Aucun").contains("800 ms").contains("2,5 %");

        // Nouveau scenario sans seuils : ceux proposes ; seuils explicites : respectes.
        String appId = json(perform(post("/api/applications"), Map.of("name", "seuils-" + UUID.randomUUID(), "url", baseUrl), ENGINEER))
                .get("id").asText();
        JsonNode created = json(perform(post("/api/scenarios"), Map.of("applicationId", appId, "name", "Par défaut"), ENGINEER));
        assertThat(created.get("maxErrorRatePercent").asDouble()).isEqualTo(2.5);
        assertThat(created.get("maxP95Ms").asLong()).isEqualTo(800);
        JsonNode explicit = json(perform(post("/api/scenarios"), Map.of("applicationId", appId, "name", "Explicite",
                "maxErrorRatePercent", 5, "maxP95Ms", 0), ENGINEER));
        assertThat(explicit.get("maxErrorRatePercent").asDouble()).isEqualTo(5.0);
        assertThat(explicit.get("maxP95Ms").isNull()).isTrue();

        // Meme seuil pour les notifications de planification.
        assertThat(com.loadpilot.backend.service.impl.ScheduleNotifier.decide("N", com.loadpilot.backend.enums.ExecutionStatus.SUCCESS,
                115L, 0.0, 16.0, com.loadpilot.backend.enums.ScheduleNotifyMode.PROBLEMS, settings.regressionPercent()).type())
                .isEqualTo(com.loadpilot.backend.enums.NotificationType.SCHEDULE_REGRESSION);
    }

    @Test
    void newScenarioDefaults_readWrite_adminOnly_audited_writtenExplicitlyAtCreation_existingScenariosUnchanged() throws Exception {
        // Valeurs initiales = celles codees jusqu'ici.
        JsonNode defaults = json(perform(get("/api/settings/thresholds"), null, VIEWER));
        assertThat(defaults.get("errorBrakeEnabled").asBoolean()).isFalse();
        assertThat(defaults.get("errorBrakeRatePercent").asDouble()).isEqualTo(20.0);
        assertThat(defaults.get("errorBrakeWindowSeconds").asInt()).isEqualTo(30);
        assertThat(defaults.get("throttleOn429").asBoolean()).isTrue();
        assertThat(defaults.get("throttleDefaultDelaySeconds").asInt()).isEqualTo(5);
        assertThat(defaults.get("throttleMaxRetries").asInt()).isEqualTo(3);
        assertThat(defaults.get("requestDetailMode").asText()).isEqualTo("FAILURES_AND_SAMPLE");

        // Scenarios crees AVANT le changement : l'un avec les valeurs de l'epoque ecrites, l'autre
        // enregistre sans ces valeurs (NULL en base, repli de l'execution).
        String appId = json(perform(post("/api/applications"), Map.of("name", "defauts-" + UUID.randomUUID(), "url", baseUrl), ENGINEER))
                .get("id").asText();
        String before = json(perform(post("/api/scenarios"), Map.of("applicationId", appId, "name", "Avant"), ENGINEER)).get("id").asText();
        String legacy = json(perform(post("/api/scenarios"), Map.of("applicationId", appId, "name", "Ancien"), ENGINEER)).get("id").asText();
        jdbc.update("UPDATE scenario SET throttle_on_429 = NULL, throttle_default_delay_seconds = NULL, throttle_max_retries = NULL, "
                + "error_brake_rate_percent = NULL, error_brake_window_seconds = NULL WHERE id = ?", UUID.fromString(legacy));

        Map<String, Object> body = new HashMap<>(Map.of("maxErrorRatePercent", 1, "maxP95Ms", 0, "regressionPercent", 20));
        body.putAll(Map.of("errorBrakeEnabled", true, "errorBrakeRatePercent", 35, "errorBrakeWindowSeconds", 60, "throttleOn429", false,
                "throttleDefaultDelaySeconds", 12, "throttleMaxRetries", 1, "requestDetailMode", "FAILURES"));
        assertThat(perform(put("/api/settings/thresholds"), body, ENGINEER).getResponse().getStatus()).as("Super admin seulement").isEqualTo(403);
        Map<String, Object> invalid = new HashMap<>(body);
        invalid.put("errorBrakeWindowSeconds", 2);
        assertThat(perform(put("/api/settings/thresholds"), invalid, ADMIN).getResponse().getStatus()).isEqualTo(400);
        invalid.put("errorBrakeWindowSeconds", 60);
        invalid.put("throttleMaxRetries", 11);
        assertThat(perform(put("/api/settings/thresholds"), invalid, ADMIN).getResponse().getStatus()).isEqualTo(400);

        JsonNode updated = json(perform(put("/api/settings/thresholds"), body, ADMIN));
        assertThat(updated.get("errorBrakeEnabled").asBoolean()).isTrue();
        assertThat(updated.get("errorBrakeRatePercent").asDouble()).isEqualTo(35.0);
        assertThat(updated.get("throttleOn429").asBoolean()).isFalse();
        assertThat(updated.get("requestDetailMode").asText()).isEqualTo("FAILURES");
        assertThat(json(perform(get("/api/settings/thresholds"), null, VIEWER)).get("throttleDefaultDelaySeconds").asInt()).isEqualTo(12);
        AuditLog entry = audits(AuditAction.UPDATE, "thresholds").get(0);
        assertThat(entry.getChanges()).contains("Frein automatique proposé").contains("Activé").contains("35 %")
                .contains("Ralentissement sur 429 proposé").contains("Désactivé").contains("12 s").contains("Échecs + échantillon");

        // Ancien client (seuils seulement) : les reglages des nouveaux scenarios restent inchanges.
        json(perform(put("/api/settings/thresholds"), Map.of("maxErrorRatePercent", 1, "maxP95Ms", 0, "regressionPercent", 20), ADMIN));
        assertThat(settings.defaultErrorBrakeRate()).isEqualTo(35.0);

        // Nouveau scenario sans ces champs : valeurs systeme ECRITES dans le scenario.
        JsonNode created = json(perform(post("/api/scenarios"), Map.of("applicationId", appId, "name", "Nouveau"), ENGINEER));
        assertThat(created.get("errorBrakeRatePercent").asDouble()).isEqualTo(35.0);
        assertThat(created.get("errorBrakeWindowSeconds").asInt()).isEqualTo(60);
        assertThat(created.get("throttleOn429").asBoolean()).isFalse();
        assertThat(created.get("throttleDefaultDelaySeconds").asInt()).isEqualTo(12);
        assertThat(created.get("throttleMaxRetries").asInt()).isEqualTo(1);
        assertThat(created.get("requestDetailMode").asText()).isEqualTo("FAILURES");
        assertThat(jdbc.queryForObject("SELECT throttle_on_429 FROM scenario WHERE id = ?", Boolean.class,
                UUID.fromString(created.get("id").asText()))).as("valeur explicite, pas NULL").isFalse();

        // Choix explicites de la requete (formulaire) : toujours gardes, frein a 0 = desactive.
        JsonNode explicit = json(perform(post("/api/scenarios"), Map.of("applicationId", appId, "name", "Explicite",
                "errorBrakeRatePercent", 0, "throttleOn429", true, "throttleDefaultDelaySeconds", 7, "requestDetailMode", "NONE"), ENGINEER));
        assertThat(explicit.get("errorBrakeRatePercent").isNull()).isTrue();
        assertThat(explicit.get("throttleOn429").asBoolean()).isTrue();
        assertThat(explicit.get("throttleDefaultDelaySeconds").asInt()).isEqualTo(7);
        assertThat(explicit.get("throttleMaxRetries").asInt()).as("champ absent : valeur systeme").isEqualTo(1);
        assertThat(explicit.get("requestDetailMode").asText()).isEqualTo("NONE");

        // Scenarios existants : inchanges (valeurs de l'epoque, ou NULL toujours NULL), meme apres une modification.
        JsonNode beforeNow = json(perform(get("/api/scenarios/" + before), null, ENGINEER));
        assertThat(beforeNow.get("throttleOn429").asBoolean()).isTrue();
        assertThat(beforeNow.get("throttleDefaultDelaySeconds").asInt()).isEqualTo(5);
        assertThat(beforeNow.get("throttleMaxRetries").asInt()).isEqualTo(3);
        assertThat(beforeNow.get("errorBrakeRatePercent").isNull()).isTrue();
        assertThat(beforeNow.get("requestDetailMode").asText()).isEqualTo("FAILURES_AND_SAMPLE");
        json(perform(put("/api/scenarios/" + legacy), Map.of("applicationId", appId, "name", "Ancien modifié"), ENGINEER));
        Map<String, Object> legacyRow = jdbc.queryForMap("SELECT throttle_on_429, throttle_default_delay_seconds, throttle_max_retries, "
                + "error_brake_rate_percent, request_detail_mode FROM scenario WHERE id = ?", UUID.fromString(legacy));
        assertThat(legacyRow.get("THROTTLE_ON_429")).isNull();
        assertThat(legacyRow.get("THROTTLE_DEFAULT_DELAY_SECONDS")).isNull();
        assertThat(legacyRow.get("THROTTLE_MAX_RETRIES")).isNull();
        assertThat(legacyRow.get("ERROR_BRAKE_RATE_PERCENT")).isNull();
        assertThat(legacyRow.get("REQUEST_DETAIL_MODE")).isEqualTo("FAILURES_AND_SAMPLE");
    }

    // ---------------------------------------------------------------- stockage

    @Test
    void storage_lastPurgeResult_andAdminOnly() throws Exception {
        assertThat(perform(get("/api/settings/storage"), null, ENGINEER).getResponse().getStatus()).isEqualTo(403);
        resultsRetentionJob.purge(Instant.parse("2000-01-01T00:00:00Z"));
        JsonNode storage = json(perform(get("/api/settings/storage"), null, ADMIN));
        assertThat(storage.get("databaseSizeBytes").isNull()).as("H2 : taille inconnue, jamais inventee").isTrue();
        assertThat(storage.get("results").get("lastRunAt").asText()).isEqualTo("2000-01-01T00:00:00Z");
        assertThat(storage.get("results").get("lastDeleted").asLong()).isZero();
        assertThat(storage.get("results").get("lastSummary").asText()).isEqualTo("0 requête supprimée");
        assertThat(storage.get("results").get("nextRunAt").isNull()).as("purge desactivee en test (cron \"-\")").isTrue();

        // Journal d'audit : chaque passage est enregistre, meme sans rien supprimer.
        auditRetentionJob.purge(Instant.parse("2000-01-02T03:30:00Z"));
        JsonNode audit = json(perform(get("/api/settings/storage"), null, ADMIN)).get("audit");
        assertThat(audit.get("lastRunAt").asText()).isEqualTo("2000-01-02T03:30:00Z");
        assertThat(audit.get("lastDeleted").asLong()).isZero();
        assertThat(audit.get("lastSummary").asText()).isEqualTo("0 entrée supprimée");
    }

    @Test
    void virtualUsers_limitedByGlobalLimit_clearMessages() throws Exception {
        String appId = json(perform(post("/api/applications"), Map.of("name", "limites-" + UUID.randomUUID(), "url", baseUrl), ENGINEER))
                .get("id").asText();
        // Configuration de test : 500 par test, 200 au total -> 200 au plus pour un test.
        MvcResult tooMany = perform(post("/api/scenarios"), Map.of("applicationId", appId, "name", "Trop", "virtualUsers", 300), ENGINEER);
        assertThat(tooMany.getResponse().getStatus()).isEqualTo(400);
        assertThat(tooMany.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("Le nombre d’utilisateurs ne doit pas dépasser 200 (limite d’un test sur ce serveur).");
        assertThat(perform(post("/api/scenarios"), Map.of("applicationId", appId, "name", "Juste", "virtualUsers", 200), ENGINEER)
                .getResponse().getStatus()).isEqualTo(201);
    }

    // ---------------------------------------------------------------- integrations

    @Test
    void integrations_monitoringStatus_noSecret_adminOnly() throws Exception {
        when(keycloak.smtpInfo()).thenReturn(new KeycloakSmtpInfo("mailpit", "1025", "no-reply@loadpilot.local", "LoadPilot", false));
        Map<String, Object> monitoring = new HashMap<>(Map.of("type", "PROMETHEUS", "url", baseUrl + "/metrics?token=query-secret",
                "authType", "BEARER", "secret", SECRET));
        String okName = "monitoree-" + UUID.randomUUID();
        perform(post("/api/applications"), Map.of("name", okName, "url", baseUrl, "monitoring", monitoring), ADMIN);
        String downName = "eteinte-" + UUID.randomUUID();
        perform(post("/api/applications"), Map.of("name", downName, "url", baseUrl,
                "monitoring", Map.of("type", "PROMETHEUS", "url", "http://localhost:1/metrics", "authType", "NONE")), ADMIN);

        assertThat(perform(get("/api/settings/integrations"), null, ENGINEER).getResponse().getStatus()).isEqualTo(403);
        MvcResult result = perform(get("/api/settings/integrations"), null, ADMIN);
        String raw = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(raw).doesNotContain(SECRET).doesNotContain("query-secret").doesNotContain("password");
        JsonNode body = objectMapper.readTree(raw);
        assertThat(body.get("mail").get("enabled").asBoolean()).isFalse();
        assertThat(body.get("authenticationMail").get("host").asText()).isEqualTo("mailpit");
        JsonNode ok = null;
        JsonNode down = null;
        for (JsonNode m : body.get("monitoring")) {
            if (m.get("applicationName").asText().equals(okName)) ok = m;
            if (m.get("applicationName").asText().equals(downName)) down = m;
        }
        assertThat(ok.get("reachable").asBoolean()).isTrue();
        assertThat(ok.get("endpoint").asText()).isEqualTo(baseUrl + "/metrics");
        assertThat(down.get("reachable").asBoolean()).isFalse();
        assertThat(down.get("message").asText()).isNotBlank();

        MvcResult test = perform(post("/api/settings/integrations/mail-test"), null, ADMIN);
        assertThat(test.getResponse().getStatus()).isEqualTo(400);
        assertThat(test.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("désactivé");
    }

    // ---------------------------------------------------------------- notifications

    @Test
    void notificationPreferences_emailChannel() throws Exception {
        RequestPostProcessor me = user("notif-email-" + UUID.randomUUID(), "ROLE_PERFORMANCE_ENGINEER");
        JsonNode list = json(perform(get("/api/notification-preferences"), null, me));
        for (JsonNode p : list) assertThat(p.get("emailEnabled").asBoolean()).isFalse();
        JsonNode updated = json(perform(patch("/api/notification-preferences/EXECUTION_FAILED"), Map.of("emailEnabled", true), me));
        assertThat(updated.get("emailEnabled").asBoolean()).isTrue();
        assertThat(updated.get("enabled").asBoolean()).as("in-app inchange").isTrue();
        assertThat(perform(patch("/api/notification-preferences/EXECUTION_FAILED"), Map.of(), me).getResponse().getStatus()).isEqualTo(400);
        JsonNode channels = json(perform(get("/api/notification-preferences/channels"), null, me));
        assertThat(channels.get("email").asBoolean()).as("aucun serveur e-mail en test").isFalse();
    }

    // ---------------------------------------------------------------- limites lues par l'assistant

    @Test
    void loadLimits_readByAll_forTheWizard_loadDefaultsRemoved() throws Exception {
        for (RequestPostProcessor who : List.of(VIEWER, ENGINEER, ADMIN)) {
            JsonNode limits = json(perform(get("/api/settings/load-limits"), null, who));
            assertThat(limits.get("maxVirtualUsers").asInt()).as("min(500 par test, 200 au total)").isEqualTo(200);
            assertThat(limits.get("maxDurationSeconds").asInt()).isEqualTo(3600);
            assertThat(limits.get("maxIterations").asInt()).isEqualTo(10000);
            assertThat(limits.get("maxTargetRps").asInt()).isEqualTo(500);
            assertThat(limits.get("targetRpsAdvisedAboveVirtualUsers").asInt()).as("seuil de l'avertissement sans debit cible").isEqualTo(50);
            assertThat(limits.size()).as("les 4 limites et le seuil de l'avertissement").isEqualTo(5);
        }
        assertThat(mockMvc.perform(get("/api/settings/load-limits")).andReturn().getResponse().getStatus()).isEqualTo(401);
        // « Charge par défaut » retiree : plus de route.
        assertThat(perform(get("/api/settings/load-defaults"), null, ADMIN).getResponse().getStatus()).isEqualTo(404);
        assertThat(perform(put("/api/settings/load-defaults"), Map.of("virtualUsers", 5), ADMIN).getResponse().getStatus()).isEqualTo(404);
    }

    // ---------------------------------------------------------------- regles de charge d'un scenario

    private MvcResult saveScenario(String appId, Map<String, Object> extra) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("applicationId", appId, "name", "Charge " + UUID.randomUUID()));
        body.putAll(extra);
        return perform(post("/api/scenarios"), body, ENGINEER);
    }

    private static String message(MvcResult r) throws Exception {
        return new ObjectMapper().readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("message").asText();
    }

    @Test
    void manyVirtualUsersWithoutTargetRps_warnedNeverRefused() throws Exception {
        String appId = json(perform(post("/api/applications"), Map.of("name", "debit-" + UUID.randomUUID(), "url", baseUrl), ENGINEER)).get("id").asText();
        String warning = "Plus de 50 utilisateurs sans débit cible : le débit n’est pas borné. "
                + "Fixez un débit cible pour maîtriser la charge envoyée et la mémoire de LoadPilot.";

        // Sous le seuil, sans debit cible : rien a signaler.
        MvcResult few = saveScenario(appId, Map.of("virtualUsers", 50));
        assertThat(few.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(few).get("loadWarnings")).isEmpty();

        // Au-dessus du seuil, sans debit cible : enregistre (jamais bloquant), avec l'avertissement.
        MvcResult many = saveScenario(appId, Map.of("virtualUsers", 51));
        assertThat(many.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(many).get("loadWarnings").get(0).asText()).isEqualTo(warning);
        // Relu (liste et detail) : toujours signale.
        String id = json(many).get("id").asText();
        assertThat(json(perform(get("/api/scenarios/" + id), null, ENGINEER)).get("loadWarnings").get(0).asText()).isEqualTo(warning);

        // Au-dessus du seuil, avec debit cible : rien a signaler.
        MvcResult paced = saveScenario(appId, Map.of("virtualUsers", 120, "targetRps", 50));
        assertThat(paced.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(paced).get("loadWarnings")).isEmpty();
    }

    @Test
    void scenarioLoad_sameRulesAsWizard_onSavePublishAndLaunch() throws Exception {
        String appId = json(perform(post("/api/applications"), Map.of("name", "regles-" + UUID.randomUUID(), "url", baseUrl), ENGINEER)).get("id").asText();

        // Enregistrement d'un scenario : ramp-up >= duree refuse, message clair.
        MvcResult ramp = saveScenario(appId, Map.of("virtualUsers", 10, "rampUpSeconds", 60, "durationSeconds", 60));
        assertThat(ramp.getResponse().getStatus()).isEqualTo(400);
        assertThat(message(ramp)).isEqualTo("Le ramp-up doit être plus court que la durée.");
        assertThat(saveScenario(appId, Map.of("virtualUsers", 10, "rampUpSeconds", 59, "durationSeconds", 60)).getResponse().getStatus()).isEqualTo(201);
        assertThat(saveScenario(appId, Map.of("virtualUsers", 10, "rampUpSeconds", 600)).getResponse().getStatus())
                .as("sans duree : avertissement seulement, jamais bloquant").isEqualTo(201);
        MvcResult tooMany = saveScenario(appId, Map.of("virtualUsers", 201));
        assertThat(message(tooMany)).isEqualTo("Le nombre d’utilisateurs ne doit pas dépasser 200 (limite d’un test sur ce serveur).");
        assertThat(message(saveScenario(appId, Map.of("virtualUsers", 0)))).contains("Le nombre d’utilisateurs doit être au moins 1.");
        assertThat(message(saveScenario(appId, Map.of("thinkTimeMs", -1)))).contains("Le think time doit être au moins 0.");

        // Modification d'un scenario enregistre : meme regle.
        String id = json(saveScenario(appId, Map.of("virtualUsers", 5, "rampUpSeconds", 5, "durationSeconds", 30))).get("id").asText();
        MvcResult update = perform(put("/api/scenarios/" + id), Map.of("applicationId", appId, "name", "Modifie", "rampUpSeconds", 30, "durationSeconds", 30), ENGINEER);
        assertThat(update.getResponse().getStatus()).isEqualTo(400);
        assertThat(message(update)).isEqualTo("Le ramp-up doit être plus court que la durée.");

        // Brouillon : enregistrable tel quel, refuse a la publication.
        String draft = json(saveScenario(appId, Map.of("draft", true, "virtualUsers", 5, "rampUpSeconds", 90, "durationSeconds", 60))).get("id").asText();
        perform(post("/api/steps"), Map.of("scenarioId", draft, "name", "Accueil", "method", "GET", "url", "/ok", "order", 1), ENGINEER);
        MvcResult publish = perform(post("/api/scenarios/" + draft + "/publish"), null, ENGINEER);
        assertThat(publish.getResponse().getStatus()).isEqualTo(400);
        assertThat(message(publish)).isEqualTo("Le ramp-up doit être plus court que la durée.");

        // Ancien scenario enregistre avant la regle : lancement refuse, avec la raison.
        perform(post("/api/steps"), Map.of("scenarioId", id, "name", "Accueil", "method", "GET", "url", "/ok", "order", 1), ENGINEER);
        jdbc.update("UPDATE scenario SET ramp_up_seconds = 40 WHERE id = ?", UUID.fromString(id));
        MvcResult launch = perform(post("/api/executions"), Map.of("scenarioId", id), ENGINEER);
        assertThat(launch.getResponse().getStatus()).isEqualTo(400);
        assertThat(message(launch)).isEqualTo("Le ramp-up doit être plus court que la durée. Modifiez la charge du scénario avant de le lancer.");
    }

    // ---------------------------------------------------------------- limites a deux niveaux

    private static Map<String, Object> limits(int perTest, int total, int concurrent, int duration, int iterations, int rps) {
        return Map.of("virtualUsersPerTest", perTest, "virtualUsersTotal", total, "concurrentTests", concurrent,
                "maxDurationSeconds", duration, "maxIterations", iterations, "maxTargetRps", rps);
    }

    @Test
    void limits_defaultToCeilings_adminOnly_neverAboveCeiling_audited_immediate() throws Exception {
        JsonNode initial = json(perform(get("/api/settings/limits"), null, ADMIN));
        Map<String, int[]> byKey = new HashMap<>();
        for (JsonNode l : initial.get("limits")) byKey.put(l.get("key").asText(), new int[] {l.get("value").asInt(), l.get("ceiling").asInt()});
        // Plafonds par defaut coherents : 200 par test <= 500 au total.
        assertThat(byKey.get("virtualUsersPerTest")).containsExactly(200, 200);
        assertThat(byKey.get("virtualUsersTotal")).containsExactly(500, 500);
        assertThat(byKey.get("maxDurationSeconds")).containsExactly(3600, 3600);
        assertThat(byKey.get("maxIterations")).containsExactly(10000, 10000);
        assertThat(byKey.get("maxTargetRps")).containsExactly(500, 500);
        assertThat(initial.get("effectiveVirtualUsersPerTest").asInt()).isEqualTo(200);
        assertThat(initial.get("executionTimeoutSeconds").isNumber()).isTrue();
        assertThat(perform(get("/api/settings/limits"), null, ENGINEER).getResponse().getStatus()).isEqualTo(403);
        assertThat(perform(put("/api/settings/limits"), limits(100, 100, 5, 600, 100, 50), ENGINEER).getResponse().getStatus()).isEqualTo(403);

        MvcResult above = perform(put("/api/settings/limits"), limits(200, 500, 10, 7200, 10000, 500), ADMIN);
        assertThat(above.getResponse().getStatus()).isEqualTo(400);
        assertThat(message(above)).isEqualTo("Durée maximale d’un test : maximum 3 600 (plafond du serveur).");
        assertThat(message(perform(put("/api/settings/limits"), limits(0, 500, 10, 3600, 10000, 500), ADMIN)))
                .isEqualTo("Utilisateurs virtuels par test : au moins 1.");

        JsonNode lowered = json(perform(put("/api/settings/limits"), limits(40, 40, 3, 1800, 500, 100), ADMIN));
        assertThat(lowered.get("effectiveVirtualUsersPerTest").asInt()).isEqualTo(40);
        AuditLog entry = audits(AuditAction.UPDATE, "limits").get(0);
        assertThat(entry.getChanges()).contains("Durée maximale d’un test").contains("3 600 s").contains("1 800 s")
                .contains("Tests en même temps").contains("10 tests").contains("3 tests");

        // Effet immediat : assistant (limites lues par tous), enregistrement, capacite.
        JsonNode wizard = json(perform(get("/api/settings/load-limits"), null, ENGINEER));
        assertThat(wizard.get("maxVirtualUsers").asInt()).isEqualTo(40);
        assertThat(wizard.get("maxDurationSeconds").asInt()).isEqualTo(1800);
        assertThat(wizard.get("maxIterations").asInt()).isEqualTo(500);
        assertThat(wizard.get("maxTargetRps").asInt()).isEqualTo(100);
        String appId = json(perform(post("/api/applications"), Map.of("name", "limites-app-" + UUID.randomUUID(), "url", baseUrl), ENGINEER)).get("id").asText();
        assertThat(message(saveScenario(appId, Map.of("durationSeconds", 1801))))
                .isEqualTo("La durée ne doit pas dépasser 1 800 s (limite d’un test sur ce serveur).");
        assertThatThrownBy(() -> registry.reserveCapacity(41)).hasMessageContaining("limite d'un test sur ce serveur (40");
    }

    private Map<String, Integer> limitValues() throws Exception {
        Map<String, Integer> values = new HashMap<>();
        for (JsonNode l : json(perform(get("/api/settings/limits"), null, ADMIN)).get("limits")) values.put(l.get("key").asText(), l.get("value").asInt());
        return values;
    }

    @Test
    void limits_totalBelowPerTest_alwaysRefused_nothingSaved_consistentAccepted() throws Exception {
        // Plafonds par defaut : 200 par test, 500 au total (un test au plus 40 % du budget).
        assertThat(limitValues()).containsEntry("virtualUsersPerTest", 200).containsEntry("virtualUsersTotal", 500);
        // Chaque champ reste borne par son propre plafond (controle en premier).
        assertThat(message(perform(put("/api/settings/limits"), limits(300, 500, 10, 3600, 10000, 500), ADMIN)))
                .isEqualTo("Utilisateurs virtuels par test : maximum 200 (plafond du serveur).");
        assertThat(message(perform(put("/api/settings/limits"), limits(150, 600, 10, 3600, 10000, 500), ADMIN)))
                .isEqualTo("Utilisateurs virtuels au total : maximum 500 (plafond du serveur).");

        // « Au total » sous « par test » : refuse, rien n'est enregistre - total baisse seul...
        int auditsBefore = audits(AuditAction.UPDATE, "limits").size();
        MvcResult lowered = perform(put("/api/settings/limits"), limits(200, 150, 10, 3600, 10000, 500), ADMIN);
        assertThat(lowered.getResponse().getStatus()).isEqualTo(400);
        assertThat(message(lowered)).isEqualTo("Le total doit être supérieur ou égal au maximum par test.");
        // ... ou « par test » monte au-dessus du total.
        assertThat(message(perform(put("/api/settings/limits"), limits(180, 120, 10, 3600, 10000, 500), ADMIN)))
                .isEqualTo("Le total doit être supérieur ou égal au maximum par test.");
        assertThat(limitValues()).containsEntry("virtualUsersPerTest", 200).containsEntry("virtualUsersTotal", 500);
        assertThat(audits(AuditAction.UPDATE, "limits")).as("aucun enregistrement refuse n'est journalise comme modifie").hasSize(auditsBefore);

        // Egal : accepte ; total au-dessus : accepte, sans ajustement automatique.
        JsonNode equal = json(perform(put("/api/settings/limits"), limits(150, 150, 10, 3600, 10000, 500), ADMIN));
        assertThat(equal.get("notices").size()).isZero();
        JsonNode raised = json(perform(put("/api/settings/limits"), limits(100, 400, 10, 3600, 10000, 500), ADMIN));
        assertThat(raised.get("notices").size()).isZero();
        assertThat(limitValues()).containsEntry("virtualUsersPerTest", 100).containsEntry("virtualUsersTotal", 400);
        assertThat(raised.get("effectiveVirtualUsersPerTest").asInt()).isEqualTo(100);

        // Ancienne configuration incoherente deja en base (180 par test, 150 au total) : jamais appliquee telle
        // quelle (par test borne au total), corrigee au prochain enregistrement coherent.
        settings.set("limits.virtual-users-per-test", "180", "ancien");
        settings.set("limits.virtual-users-total", "150", "ancien");
        assertThat(limitValues()).containsEntry("virtualUsersPerTest", 150).containsEntry("virtualUsersTotal", 150);
        assertThat(json(perform(get("/api/settings/load-limits"), null, ENGINEER)).get("maxVirtualUsers").asInt()).isEqualTo(150);
        json(perform(put("/api/settings/limits"), limits(150, 150, 5, 3600, 10000, 500), ADMIN));
        assertThat(settings.getInt("limits.virtual-users-per-test", 0)).isEqualTo(150);
        assertThat(audits(AuditAction.UPDATE, "limits").get(0).getChanges()).contains("180 utilisateurs").contains("150 utilisateurs");
    }

    @Test
    void newLoadRules_duration_iterations_rps_thinkTime_rampUp() throws Exception {
        String appId = json(perform(post("/api/applications"), Map.of("name", "nouvelles-regles-" + UUID.randomUUID(), "url", baseUrl), ENGINEER)).get("id").asText();
        assertThat(message(saveScenario(appId, Map.of("durationSeconds", 3601)))).isEqualTo("La durée ne doit pas dépasser 3 600 s (limite d’un test sur ce serveur).");
        assertThat(message(saveScenario(appId, Map.of("iterations", 10001)))).isEqualTo("Le nombre d’itérations ne doit pas dépasser 10 000 (limite d’un test sur ce serveur).");
        assertThat(message(saveScenario(appId, Map.of("targetRps", 501)))).isEqualTo("Le débit cible ne doit pas dépasser 500 req/s (limite d’un test sur ce serveur).");
        assertThat(message(saveScenario(appId, Map.of("thinkTimeMs", 60001)))).isEqualTo("Le think time ne doit pas dépasser 60 000 ms.");
        assertThat(message(saveScenario(appId, Map.of("rampUpSeconds", 3601)))).isEqualTo("Le ramp-up ne doit pas dépasser 3 600 s (durée maximale d’un test).");
        assertThat(saveScenario(appId, Map.of("durationSeconds", 3600, "iterations", 10000, "targetRps", 500, "thinkTimeMs", 60000, "rampUpSeconds", 3599))
                .getResponse().getStatus()).isEqualTo(201);
        // Minimums (memes messages que l'assistant).
        assertThat(message(saveScenario(appId, Map.of("durationSeconds", 0)))).contains("La durée doit être au moins 1.");
        assertThat(message(saveScenario(appId, Map.of("iterations", 0)))).contains("Le nombre d’itérations doit être au moins 1.");
        assertThat(message(saveScenario(appId, Map.of("targetRps", 0)))).contains("Le débit cible doit être au moins 1.");
    }

    @Test
    void loweredLimit_existingScenarioKept_launchAndScheduleRefused() throws Exception {
        String appId = json(perform(post("/api/applications"), Map.of("name", "baisse-" + UUID.randomUUID(), "url", baseUrl), ENGINEER)).get("id").asText();
        String id = json(saveScenario(appId, Map.of("virtualUsers", 2, "rampUpSeconds", 1, "durationSeconds", 600))).get("id").asText();
        perform(post("/api/steps"), Map.of("scenarioId", id, "name", "Accueil", "method", "GET", "url", "/ok", "order", 1), ENGINEER);

        assertThat(perform(put("/api/settings/limits"), limits(200, 200, 10, 300, 10000, 500), ADMIN).getResponse().getStatus()).isEqualTo(200);

        assertThat(json(perform(get("/api/scenarios/" + id), null, ENGINEER)).get("durationSeconds").asInt()).as("jamais modifie").isEqualTo(600);
        MvcResult launch = perform(post("/api/executions"), Map.of("scenarioId", id), ENGINEER);
        assertThat(launch.getResponse().getStatus()).isEqualTo(400);
        assertThat(message(launch)).isEqualTo("La durée ne doit pas dépasser 300 s (limite d’un test sur ce serveur). Modifiez la charge du scénario avant de le lancer.");
        MvcResult schedule = perform(post("/api/scheduled-executions"), Map.of("scenarioId", id, "name", "Nocturne", "scheduleType", "ONE_TIME",
                "runAt", Instant.now().plusSeconds(86400).toString(), "timezone", "UTC"), ENGINEER);
        assertThat(schedule.getResponse().getStatus()).isEqualTo(400);
        assertThat(message(schedule)).isEqualTo("La durée ne doit pas dépasser 300 s (limite d’un test sur ce serveur). Modifiez la charge du scénario avant de le planifier.");
        assertThat(json(perform(get("/api/settings/load-limits"), null, ENGINEER)).get("maxDurationSeconds").asInt()).isEqualTo(300);
    }
}
