package com.loadpilot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loadpilot.backend.dto.request.AuditLogFilterRequest;
import com.loadpilot.backend.dto.response.AuditLogResponse;
import com.loadpilot.backend.dto.response.AuditStatsResponse;
import com.loadpilot.backend.dto.response.PagedResponse;
import com.loadpilot.backend.entity.AuditLog;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.mapper.AuditLogMapper;
import com.loadpilot.backend.repository.AuditLogRepository;
import com.loadpilot.backend.service.impl.AuditLogServiceImpl;
import com.loadpilot.backend.service.impl.AuditLogWriter;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Test UNITAIRE pur (Mockito, aucun Spring/H2) de AuditLogServiceImpl :
 * resolution de l'utilisateur courant/IP depuis le contexte, garantie
 * qu'un echec d'ecriture n'est jamais propage, troncature, echappement CSV,
 * agregation des statistiques. Voir AuditLogRepositoryTest/
 * AuditLogServiceImplIntegrationTest pour la persistance H2 reelle.
 */
@ExtendWith(MockitoExtension.class)
class AuditLogServiceImplUnitTest {

    @Mock
    private AuditLogRepository auditLogRepository;
    @Mock
    private AuditLogMapper auditLogMapper;
    @Mock
    private AuditLogWriter auditLogWriter;

    @InjectMocks
    private AuditLogServiceImpl service;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private Jwt jwtWithSubjectAndUsername(String subject, String username) {
        return Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject(subject)
                .claim("preferred_username", username)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }

    @Test
    void record_withAuthenticatedJwtAndRequestContext_capturesUserIdUsernameAndIp() {
        Jwt jwt = jwtWithSubjectAndUsername("user-123", "alice");
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(token);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.10");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        service.record(AuditAction.CREATE, AuditModule.APPLICATION, AuditResult.SUCCESS, "desc");

        verify(auditLogWriter).write(captor.capture());
        AuditLog entry = captor.getValue();
        assertThat(entry.getUserId()).isEqualTo("user-123");
        assertThat(entry.getUsername()).isEqualTo("alice");
        assertThat(entry.getIp()).isEqualTo("192.168.1.10");
        assertThat(entry.getAction()).isEqualTo(AuditAction.CREATE);
        assertThat(entry.getModule()).isEqualTo(AuditModule.APPLICATION);
        assertThat(entry.getResult()).isEqualTo(AuditResult.SUCCESS);
        assertThat(entry.getDescription()).isEqualTo("desc");
        assertThat(entry.getDate()).isNotNull();
    }

    @Test
    void record_withoutAuthentication_userIdAndUsernameAreNull() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        service.record(AuditAction.READ, AuditModule.DASHBOARD, AuditResult.SUCCESS, "desc");

        verify(auditLogWriter).write(captor.capture());
        assertThat(captor.getValue().getUserId()).isNull();
        assertThat(captor.getValue().getUsername()).isNull();
    }

    @Test
    void record_withNonJwtAuthentication_userIdAndUsernameAreNull() {
        // Ex: un principal non-JWT (ne devrait pas arriver dans cette
        // architecture 100% Resource Server, mais doit rester defensif).
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("someone", "n/a", "ROLE_SUPER_ADMIN"));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        service.record(AuditAction.READ, AuditModule.DASHBOARD, AuditResult.SUCCESS, "desc");

        verify(auditLogWriter).write(captor.capture());
        assertThat(captor.getValue().getUserId()).isNull();
    }

    @Test
    void record_withoutHttpRequestContext_ipIsNull() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        service.record(AuditAction.READ, AuditModule.DASHBOARD, AuditResult.SUCCESS, "desc");

        verify(auditLogWriter).write(captor.capture());
        assertThat(captor.getValue().getIp()).isNull();
    }

    @Test
    void record_writerThrows_isSwallowedAndNeverPropagatesToCaller() {
        doThrow(new RuntimeException("boom")).when(auditLogWriter).write(any());

        assertThatCode(() -> service.record(AuditAction.DELETE, AuditModule.STEP, AuditResult.FAILURE, "desc"))
                .doesNotThrowAnyException();
    }

    @Test
    void record_longDescription_isTruncatedTo500Characters() {
        String longDescription = "x".repeat(600);
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);

        service.record(AuditAction.UPDATE, AuditModule.APPLICATION, AuditResult.SUCCESS, longDescription);

        verify(auditLogWriter).write(captor.capture());
        assertThat(captor.getValue().getDescription()).hasSize(500);
    }

    @Test
    void record_nullDescription_staysNull() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        service.record(AuditAction.READ, AuditModule.DASHBOARD, AuditResult.SUCCESS, null);

        verify(auditLogWriter).write(captor.capture());
        assertThat(captor.getValue().getDescription()).isNull();
    }

    @Test
    void getById_unknownId_throwsResourceNotFound() {
        UUID id = UUID.randomUUID();
        when(auditLogRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(id)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void search_mapsPageResultToPagedResponse() {
        AuditLog entry = AuditLog.builder().id(UUID.randomUUID()).action(AuditAction.CREATE)
                .module(AuditModule.APPLICATION).result(AuditResult.SUCCESS).date(Instant.now()).build();
        Page<AuditLog> page = new PageImpl<>(List.of(entry), PageRequest.of(0, 20), 1);
        AuditLogResponse response = new AuditLogResponse(entry.getId().toString(), null, null,
                AuditAction.CREATE, AuditModule.APPLICATION, entry.getDate(), null, AuditResult.SUCCESS, null);

        when(auditLogRepository.findAll(org.mockito.ArgumentMatchers.<Specification<AuditLog>>any(), any(PageRequest.class)))
                .thenReturn(page);
        when(auditLogMapper.toResponseList(List.of(entry))).thenReturn(List.of(response));

        PagedResponse<AuditLogResponse> result = service.search(
                new AuditLogFilterRequest(null, null, null, null, null, null, null), 0, 20);

        assertThat(result.content()).containsExactly(response);
        assertThat(result.page()).isEqualTo(0);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.totalPages()).isEqualTo(1);
    }

    @Test
    void getStats_aggregatesRealRepositoryCounts() {
        when(auditLogRepository.count()).thenReturn(10L);
        when(auditLogRepository.countByResult(AuditResult.SUCCESS)).thenReturn(7L);
        when(auditLogRepository.countByResult(AuditResult.FAILURE)).thenReturn(3L);
        when(auditLogRepository.countGroupedByModule())
                .thenReturn(List.<Object[]>of(new Object[]{AuditModule.APPLICATION, 5L}));
        when(auditLogRepository.countGroupedByAction())
                .thenReturn(List.<Object[]>of(new Object[]{AuditAction.CREATE, 5L}));

        AuditStatsResponse stats = service.getStats();

        assertThat(stats.totalActions()).isEqualTo(10L);
        assertThat(stats.successfulActions()).isEqualTo(7L);
        assertThat(stats.failedActions()).isEqualTo(3L);
        assertThat(stats.actionsByModule()).containsEntry("APPLICATION", 5L);
        assertThat(stats.actionsByAction()).containsEntry("CREATE", 5L);
    }

    @Test
    void exportCsv_escapesCommasQuotesAndNewlines() {
        UUID id = UUID.randomUUID();
        Instant date = Instant.parse("2024-01-01T00:00:00Z");
        AuditLog entry = AuditLog.builder().id(id).userId("u1").username("alice")
                .action(AuditAction.CREATE).module(AuditModule.APPLICATION).date(date)
                .ip("127.0.0.1").result(AuditResult.SUCCESS)
                .description("Contains, a comma \"and quotes\"\nand a newline").build();
        AuditLogResponse response = new AuditLogResponse(id.toString(), "u1", "alice", AuditAction.CREATE,
                AuditModule.APPLICATION, date, "127.0.0.1", AuditResult.SUCCESS, entry.getDescription());

        when(auditLogRepository.findAll(org.mockito.ArgumentMatchers.<Specification<AuditLog>>any(), any(Sort.class)))
                .thenReturn(List.of(entry));
        when(auditLogMapper.toResponseList(List.of(entry))).thenReturn(List.of(response));

        String csv = service.exportCsv(new AuditLogFilterRequest(null, null, null, null, null, null, null));

        assertThat(csv).startsWith("id,userId,username,action,module,date,ip,result,description\r\n");
        assertThat(csv).contains("\"Contains, a comma \"\"and quotes\"\"\nand a newline\"");
    }

    @Test
    void exportCsv_noRows_returnsOnlyHeader() {
        when(auditLogRepository.findAll(org.mockito.ArgumentMatchers.<Specification<AuditLog>>any(), any(Sort.class)))
                .thenReturn(List.of());
        when(auditLogMapper.toResponseList(List.of())).thenReturn(List.of());

        String csv = service.exportCsv(new AuditLogFilterRequest(null, null, null, null, null, null, null));

        assertThat(csv).isEqualTo("id,userId,username,action,module,date,ip,result,description\r\n");
    }
}
