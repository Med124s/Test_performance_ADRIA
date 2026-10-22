package com.loadpilot.backend.service.audit;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.time.Duration;
import java.time.Instant;
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

/**
 * LOADPILOT_AUDIT_RETENTION_OVERRIDE=10m (tests locaux) : la purge reelle supprime les entrees de plus
 * de 10 minutes, le dit au journal ; Parametres > Systeme affiche et valide toujours comme avant.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "app.audit.retention-override=10m")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditRetentionOverrideTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private AppSettingsService settings;
    @Autowired private AuditRetentionJob retentionJob;

    @MockBean private JwtDecoder jwtDecoder;
    @MockBean private KeycloakAdminClient keycloakAdminClient;

    @Test
    void purge_usesTheTestDuration_settingsPageUnchanged() throws Exception {
        settings.set(AppSettingsService.AUDIT_RETENTION_DAYS, "365", "test");
        Instant now = Instant.now();
        String marker = "override-" + UUID.randomUUID();
        AuditLog old = save(now.minus(Duration.ofMinutes(11)), marker + "-old");
        AuditLog recent = save(now.minus(Duration.ofMinutes(9)), marker + "-recent");

        assertThat(retentionJob.purge(now)).isGreaterThanOrEqualTo(1);

        assertThat(auditLogRepository.findById(old.getId())).as("11 min > 10 min : supprimee").isEmpty();
        assertThat(auditLogRepository.findById(recent.getId())).as("9 min : gardee").isPresent();
        AuditLog purge = auditLogRepository.findAll().stream().filter(a -> a.getAction() == AuditAction.PURGE).reduce((a, b) -> b).orElseThrow();
        assertThat(purge.getDescription())
                .contains("de plus de 10 minutes (durée de test LOADPILOT_AUDIT_RETENTION_OVERRIDE) supprimée");

        // Parametres > Systeme : duree stockee affichee telle quelle, minimum de 30 jours toujours applique.
        var admin = jwt().jwt(j -> j.claim("preferred_username", "admin-test")).authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
        mockMvc.perform(get("/api/settings/audit").with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.retentionDays").value(365));
        mockMvc.perform(put("/api/settings/audit").with(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"logReads\":false,\"retentionDays\":5}"))
                .andExpect(status().isBadRequest());
    }

    private AuditLog save(Instant date, String description) {
        return auditLogRepository.saveAndFlush(AuditLog.builder().action(AuditAction.CREATE).module(AuditModule.APPLICATION)
                .result(AuditResult.SUCCESS).date(date).description(description).build());
    }
}
