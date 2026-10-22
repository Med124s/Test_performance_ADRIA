package com.loadpilot.backend.service.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.loadpilot.backend.entity.AuditLog;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.repository.AuditLogRepository;
import com.loadpilot.backend.service.AppSettingsService;
import com.loadpilot.backend.service.keycloak.KeycloakAdminClient;
import com.loadpilot.backend.service.keycloak.KeycloakSecurityEvent;
import com.loadpilot.backend.service.keycloak.KeycloakUserRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
 * Reglages du journal (droits, validation, journalisation de la modification),
 * purge selon la duree de conservation, et import des evenements de securite
 * du systeme d'authentification (connexion, echec avec nom saisi et IP, blocage,
 * deconnexion, mot de passe) sans jamais d'import en double.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditSecurityAndRetentionTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private AppSettingsService settings;
    @Autowired private AuditRetentionJob retentionJob;
    @Autowired private SecurityEventImporter importer;

    @MockBean private JwtDecoder jwtDecoder;
    @MockBean private KeycloakAdminClient keycloakAdminClient;

    private static final RequestPostProcessor AS_SUPER_ADMIN =
            jwt().jwt(j -> j.claim("preferred_username", "admin-test")).authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
    private static final RequestPostProcessor AS_ENGINEER =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_PERFORMANCE_ENGINEER"));

    @BeforeEach
    void resetSettings() {
        settings.set(AppSettingsService.AUDIT_LOG_READS, "false", "test");
        settings.set(AppSettingsService.AUDIT_RETENTION_DAYS, "365", "test");
        settings.set(AppSettingsService.SECURITY_EVENTS_CURSOR, "0", "test");
    }

    // ------------------------------------------------------------ reglages

    @Test
    void auditSettings_defaults_update_validation_rights_andTheChangeIsAudited() throws Exception {
        mockMvc.perform(get("/api/settings/audit").with(AS_SUPER_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logReads").value(false))
                .andExpect(jsonPath("$.retentionDays").value(365));

        mockMvc.perform(put("/api/settings/audit").with(AS_ENGINEER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"logReads\":true,\"retentionDays\":90}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/settings/audit").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"logReads\":true,\"retentionDays\":5}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/settings/audit").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"logReads\":true,\"retentionDays\":90}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logReads").value(true))
                .andExpect(jsonPath("$.retentionDays").value(90));
        assertThat(settings.logReads()).isTrue();

        AuditLog change = auditLogRepository.findAll().stream()
                .filter(a -> a.getModule() == AuditModule.SETTINGS && a.getAction() == AuditAction.UPDATE)
                .reduce((a, b) -> b).orElseThrow();
        assertThat(change.getChanges())
                .contains("{\"label\":\"Journaliser aussi les consultations\",\"before\":\"Non\",\"after\":\"Oui\"}")
                .contains("{\"label\":\"Durée de conservation\",\"before\":\"365 jours\",\"after\":\"90 jours\"}");
    }

    // ------------------------------------------------------------ conservation

    @Test
    void purge_deletesOnlyEntriesOlderThanTheRetention_andRecordsItAsSystem() {
        settings.set(AppSettingsService.AUDIT_RETENTION_DAYS, "30", "test");
        Instant now = Instant.now();
        String marker = "purge-" + UUID.randomUUID();
        AuditLog old = save(AuditLog.builder().action(AuditAction.CREATE).module(AuditModule.APPLICATION).result(AuditResult.SUCCESS)
                .date(now.minus(Duration.ofDays(31))).description(marker + "-old").build());
        AuditLog recent = save(AuditLog.builder().action(AuditAction.CREATE).module(AuditModule.APPLICATION).result(AuditResult.SUCCESS)
                .date(now.minus(Duration.ofDays(29))).description(marker + "-recent").build());

        int deleted = retentionJob.purge(now);

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(auditLogRepository.findById(old.getId())).isEmpty();
        assertThat(auditLogRepository.findById(recent.getId())).isPresent();
        AuditLog purge = auditLogRepository.findAll().stream().filter(a -> a.getAction() == AuditAction.PURGE).reduce((a, b) -> b).orElseThrow();
        assertThat(purge.getActorType()).isEqualTo("SYSTEM");
        assertThat(purge.getActorSource()).isEqualTo("Purge automatique quotidienne");
        assertThat(purge.getDescription()).contains("de plus de 30 jours supprimée");
    }

    /**
     * Chemin reel du cron : scheduledPurge() (et non purge() appele directement). Regression : l'appel
     * interne ignorait @Transactional, la suppression etait refusee (TransactionRequiredException) et
     * rien n'etait jamais purge.
     */
    @Test
    void scheduledPurge_cronPath_deletesEntriesOlderThanTheRetention_andRecordsTheLastPass() {
        settings.set(AppSettingsService.AUDIT_RETENTION_DAYS, "14", "test");
        settings.set(AppSettingsService.AUDIT_LAST_PURGE, "{}", "test");
        Instant now = Instant.now();
        String marker = "cron-" + UUID.randomUUID();
        AuditLog old = save(AuditLog.builder().action(AuditAction.CREATE).module(AuditModule.APPLICATION).result(AuditResult.SUCCESS)
                .date(now.minus(Duration.ofDays(20))).description(marker + "-old").build());
        AuditLog recent = save(AuditLog.builder().action(AuditAction.CREATE).module(AuditModule.APPLICATION).result(AuditResult.SUCCESS)
                .date(now.minus(Duration.ofDays(10))).description(marker + "-recent").build());

        retentionJob.scheduledPurge();

        assertThat(auditLogRepository.findById(old.getId())).as("20 jours > 14 jours : supprimee").isEmpty();
        assertThat(auditLogRepository.findById(recent.getId())).as("10 jours : gardee").isPresent();
        AuditLog purge = auditLogRepository.findAll().stream().filter(a -> a.getAction() == AuditAction.PURGE).reduce((a, b) -> b).orElseThrow();
        assertThat(purge.getDescription()).contains("de plus de 14 jours supprimée");
        assertThat(settings.get(AppSettingsService.AUDIT_LAST_PURGE).orElseThrow()).as("bilan du passage (Paramètres > Système)")
                .startsWith("{\"at\":\"" + now.toString().substring(0, 10)).containsPattern("\"deleted\":[1-9]");
    }

    private AuditLog save(AuditLog entry) {
        return auditLogRepository.saveAndFlush(entry);
    }

    // ------------------------------------------------------------ evenements de securite

    @Test
    void securityEvents_areImported_inFrench_withTypedNameAndIp_neverTwice() {
        long t = Instant.now().toEpochMilli();
        String run = UUID.randomUUID().toString().substring(0, 8);
        List<KeycloakSecurityEvent> events = List.of(
                // Du plus recent au plus ancien, comme Keycloak.
                new KeycloakSecurityEvent("ev-logout-" + run, "LOGOUT", t, "kc-lina", null, "172.17.0.1", null),
                new KeycloakSecurityEvent("ev-lock-" + run, "USER_DISABLED_BY_TEMPORARY_LOCKOUT", t - 1000, "kc-lina", null, "172.17.0.1", null),
                new KeycloakSecurityEvent("ev-locked-" + run, "LOGIN_ERROR", t - 2000, "kc-lina", "lina", "172.17.0.1", "user_temporarily_disabled"),
                new KeycloakSecurityEvent("ev-unknown-" + run, "LOGIN_ERROR", t - 3000, null, "inconnu@exemple.com", "203.0.113.7", "user_not_found"),
                new KeycloakSecurityEvent("ev-bad-" + run, "LOGIN_ERROR", t - 4000, "kc-lina", "lina", "172.17.0.1", "invalid_user_credentials"),
                new KeycloakSecurityEvent("ev-pwd-" + run, "UPDATE_PASSWORD", t - 5000, "kc-lina", "lina", "172.17.0.1", null),
                new KeycloakSecurityEvent("ev-login-" + run, "LOGIN", t - 6000, "kc-lina", "lina@exemple.com", "172.17.0.1", null));
        when(keycloakAdminClient.securityEvents(eq(0), anyInt())).thenReturn(events);
        when(keycloakAdminClient.listUsers()).thenReturn(List.of(new KeycloakUserRecord("kc-lina", "lina", "lina@exemple.com", true, null)));

        assertThat(importer.importNewEvents()).isEqualTo(7);
        // Deuxieme passage : memes evenements relus, rien n'est importe deux fois.
        assertThat(importer.importNewEvents()).isZero();

        List<AuditLog> imported = auditLogRepository.findAll().stream()
                .filter(a -> a.getExternalId() != null && a.getExternalId().endsWith(run)).toList();
        assertThat(imported).hasSize(7).allSatisfy(a -> {
            assertThat(a.getModule()).isEqualTo(AuditModule.SECURITY);
            assertThat(a.getActorSource()).isEqualTo("Authentification");
            assertThat(a.getIp()).isNotNull();
        });
        AuditLog login = byExternal(imported, "ev-login-" + run);
        assertThat(login.getAction()).isEqualTo(AuditAction.LOGIN);
        assertThat(login.getUsername()).isEqualTo("lina");
        assertThat(login.getDate()).isEqualTo(Instant.ofEpochMilli(t - 6000));
        AuditLog bad = byExternal(imported, "ev-bad-" + run);
        assertThat(bad.getAction()).isEqualTo(AuditAction.LOGIN_FAILED);
        assertThat(bad.getResult()).isEqualTo(AuditResult.FAILURE);
        assertThat(bad.getDescription()).isEqualTo("Mot de passe incorrect (nom saisi : lina)");
        AuditLog unknown = byExternal(imported, "ev-unknown-" + run);
        assertThat(unknown.getUsername()).isEqualTo("inconnu@exemple.com");
        assertThat(unknown.getUserId()).isNull();
        assertThat(unknown.getIp()).isEqualTo("203.0.113.7");
        assertThat(unknown.getDescription()).isEqualTo("Compte inconnu (nom saisi : inconnu@exemple.com)");
        assertThat(byExternal(imported, "ev-lock-" + run).getAction()).isEqualTo(AuditAction.ACCOUNT_LOCKED);
        assertThat(byExternal(imported, "ev-locked-" + run).getDescription()).startsWith("Connexion refusée : compte bloqué temporairement");
        assertThat(byExternal(imported, "ev-pwd-" + run).getAction()).isEqualTo(AuditAction.PASSWORD_CHANGED);
        assertThat(byExternal(imported, "ev-logout-" + run).getAction()).isEqualTo(AuditAction.LOGOUT);
        assertThat(settings.getLong(AppSettingsService.SECURITY_EVENTS_CURSOR, 0)).isEqualTo(t);
    }

    private static AuditLog byExternal(List<AuditLog> rows, String id) {
        return rows.stream().filter(a -> id.equals(a.getExternalId())).findFirst().orElseThrow();
    }

    @Test
    void securityEvents_haveTheirOwnCategoryFilter() throws Exception {
        long t = Instant.now().toEpochMilli();
        String run = UUID.randomUUID().toString().substring(0, 8);
        when(keycloakAdminClient.securityEvents(eq(0), anyInt())).thenReturn(List.of(
                new KeycloakSecurityEvent("ev-cat-" + run, "LOGIN_ERROR", t, null, "cat-" + run, "198.51.100.1", "invalid_user_credentials")));
        when(keycloakAdminClient.listUsers()).thenReturn(List.of());
        importer.importNewEvents();

        mockMvc.perform(get("/api/audit-logs").param("category", "SECURITY").param("q", "cat-" + run).with(AS_SUPER_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].summary").value("Échec de connexion"))
                .andExpect(jsonPath("$.content[0].categoryLabel").value("Sécurité"))
                .andExpect(jsonPath("$.content[0].resultLabel").value("Échec"))
                .andExpect(jsonPath("$.content[0].ipLabel").value("198.51.100.1"));
    }
}
