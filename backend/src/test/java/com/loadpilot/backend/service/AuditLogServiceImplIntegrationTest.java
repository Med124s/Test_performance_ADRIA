package com.loadpilot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loadpilot.backend.dto.request.AuditLogFilterRequest;
import com.loadpilot.backend.dto.response.AuditLogResponse;
import com.loadpilot.backend.dto.response.AuditStatsResponse;
import com.loadpilot.backend.dto.response.PagedResponse;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifie AuditLogService avec de VRAIES donnees H2 (voir application-test.yml).
 *
 * record() ecrit toujours via AuditLogWriter en transaction REQUIRES_NEW
 * (voir Phase 12, section 15) : contrairement aux autres tests d'integration
 * de ce projet, un @Transactional de classe ici NE PROTEGERAIT PAS contre la
 * pollution inter-tests (une transaction REQUIRES_NEW committe
 * independamment n'est jamais annulee par le rollback du test appelant).
 * Chaque test utilise donc un userId Keycloak unique (authentification
 * simulee) pour s'isoler par filtrage exact, ou une comparaison delta
 * avant/apres pour les statistiques globales non filtrables par utilisateur.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuditLogServiceImplIntegrationTest {

    @MockBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private AuditLogService auditLogService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private String authenticateAsNewUser() {
        String subject = "audit-test-user-" + UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject(subject)
                .claim("preferred_username", "audit-test-username")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(token);
        return subject;
    }

    private AuditLogFilterRequest byUser(String userId) {
        return new AuditLogFilterRequest(userId, null, null, null, null, null, null);
    }

    @Test
    void record_thenSearchByUserId_findsThePersistedEntry() {
        String userId = authenticateAsNewUser();

        auditLogService.record(AuditAction.CREATE, AuditModule.APPLICATION, AuditResult.SUCCESS, "integration-create");

        PagedResponse<AuditLogResponse> result = auditLogService.search(byUser(userId), 0, 50);
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).description()).isEqualTo("integration-create");
        assertThat(result.content().get(0).userId()).isEqualTo(userId);
        assertThat(result.content().get(0).username()).isEqualTo("audit-test-username");
    }

    @Test
    void record_thenGetById_returnsThePersistedEntry() {
        String userId = authenticateAsNewUser();
        auditLogService.record(AuditAction.DELETE, AuditModule.STEP, AuditResult.FAILURE, "integration-delete-failure");

        PagedResponse<AuditLogResponse> found = auditLogService.search(byUser(userId), 0, 10);
        String id = found.content().get(0).id();

        AuditLogResponse response = auditLogService.getById(UUID.fromString(id));
        assertThat(response.action()).isEqualTo(AuditAction.DELETE);
        assertThat(response.module()).isEqualTo(AuditModule.STEP);
        assertThat(response.result()).isEqualTo(AuditResult.FAILURE);
    }

    @Test
    void getById_unknownId_throwsResourceNotFound() {
        assertThatThrownBy(() -> auditLogService.getById(UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void search_combinedFilters_appliesAndLogic() {
        String userId = authenticateAsNewUser();
        auditLogService.record(AuditAction.CREATE, AuditModule.APPLICATION, AuditResult.SUCCESS, "match");
        auditLogService.record(AuditAction.CREATE, AuditModule.SCENARIO, AuditResult.SUCCESS, "wrong-module");
        auditLogService.record(AuditAction.UPDATE, AuditModule.APPLICATION, AuditResult.SUCCESS, "wrong-action");

        PagedResponse<AuditLogResponse> result = auditLogService.search(
                new AuditLogFilterRequest(userId, null, AuditAction.CREATE, AuditModule.APPLICATION, null, null, null), 0, 50);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).description()).isEqualTo("match");
    }

    @Test
    void search_pagination_respectsPageAndSize() {
        String userId = authenticateAsNewUser();
        auditLogService.record(AuditAction.READ, AuditModule.DASHBOARD, AuditResult.SUCCESS, "1");
        auditLogService.record(AuditAction.READ, AuditModule.DASHBOARD, AuditResult.SUCCESS, "2");
        auditLogService.record(AuditAction.READ, AuditModule.DASHBOARD, AuditResult.SUCCESS, "3");

        PagedResponse<AuditLogResponse> page0 = auditLogService.search(byUser(userId), 0, 2);
        PagedResponse<AuditLogResponse> page1 = auditLogService.search(byUser(userId), 1, 2);

        assertThat(page0.content()).hasSize(2);
        assertThat(page1.content()).hasSize(1);
        assertThat(page0.totalElements()).isEqualTo(3);
        assertThat(page0.totalPages()).isEqualTo(2);
    }

    @Test
    void getStats_deltaReflectsNewlyRecordedEntries() {
        AuditStatsResponse before = auditLogService.getStats();

        authenticateAsNewUser();
        auditLogService.record(AuditAction.EXECUTE, AuditModule.EXECUTION, AuditResult.SUCCESS, "s");
        auditLogService.record(AuditAction.EXECUTE, AuditModule.EXECUTION, AuditResult.FAILURE, "f");

        AuditStatsResponse after = auditLogService.getStats();

        assertThat(after.totalActions()).isEqualTo(before.totalActions() + 2);
        assertThat(after.successfulActions()).isEqualTo(before.successfulActions() + 1);
        assertThat(after.failedActions()).isEqualTo(before.failedActions() + 1);
    }

    @Test
    void exportCsv_filteredByUserId_containsOnlyThatUsersEntry() {
        String userId = authenticateAsNewUser();
        auditLogService.record(AuditAction.CANCEL, AuditModule.EXECUTION, AuditResult.SUCCESS, "cancel-desc");

        String csv = auditLogService.exportCsv(byUser(userId));
        String[] lines = csv.split("\r\n");

        assertThat(lines[0]).isEqualTo("id,userId,username,action,module,date,ip,result,description");
        assertThat(lines).hasSize(2);
        assertThat(lines[1]).contains(userId).contains("CANCEL").contains("EXECUTION").contains("SUCCESS").contains("cancel-desc");
    }
}
