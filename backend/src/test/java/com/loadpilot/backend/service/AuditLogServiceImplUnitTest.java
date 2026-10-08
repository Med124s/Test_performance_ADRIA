package com.loadpilot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.dto.request.AuditLogFilterRequest;
import com.loadpilot.backend.dto.response.AuditLogResponse;
import com.loadpilot.backend.dto.response.PagedResponse;
import com.loadpilot.backend.entity.AuditLog;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.exception.ConflictException;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.repository.ApplicationRepository;
import com.loadpilot.backend.repository.AuditLogRepository;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.ScenarioRepository;
import com.loadpilot.backend.repository.ScheduledExecutionRepository;
import com.loadpilot.backend.security.RequestIpResolver;
import com.loadpilot.backend.service.audit.AuditChange;
import com.loadpilot.backend.service.audit.AuditEvent;
import com.loadpilot.backend.service.audit.AuditLogPresenter;
import com.loadpilot.backend.service.audit.AuditTargetType;
import com.loadpilot.backend.service.impl.AuditLogServiceImpl;
import com.loadpilot.backend.service.impl.AuditLogWriter;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
 * Test UNITAIRE (Mockito, aucun Spring/H2) de AuditLogServiceImpl : acteur et
 * IP resolus depuis la requete, consultations non journalisees par defaut,
 * anti-doublon, secrets masques, acteur Systeme, evenements importes, export
 * CSV en francais. Persistance H2 reelle : voir AuditLogRepositoryTest /
 * AuditLogServiceImplIntegrationTest.
 */
class AuditLogServiceImplUnitTest {

    private AuditLogRepository auditLogRepository;
    private AuditLogWriter auditLogWriter;
    private AppSettingsService settings;
    private AuditLogServiceImpl service;

    @BeforeEach
    void setUp() {
        auditLogRepository = mock(AuditLogRepository.class);
        auditLogWriter = mock(AuditLogWriter.class);
        settings = mock(AppSettingsService.class);
        AuditLogPresenter presenter = new AuditLogPresenter(new ObjectMapper().findAndRegisterModules(), mock(ApplicationRepository.class),
                mock(ScenarioRepository.class), mock(ExecutionRepository.class), mock(ScheduledExecutionRepository.class));
        service = new AuditLogServiceImpl(auditLogRepository, auditLogWriter, presenter, settings, new RequestIpResolver(List.of("10.0.0.5")));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private void authenticateAs(String subject, String username) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(subject).claim("preferred_username", username)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    }

    private MockHttpServletRequest request(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        request.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0) Chrome/141.0");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    private AuditLog written() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogWriter).write(captor.capture());
        return captor.getValue();
    }

    // ---------------------------------------------------------------- enregistrement

    @Test
    void record_capturesUserIpBrowserTargetAndChanges() {
        authenticateAs("user-123", "alice");
        request("192.168.1.10");

        service.record(AuditEvent.of(AuditAction.UPDATE, AuditModule.SCENARIO)
                .target(AuditTargetType.SCENARIO, "sc-1", "Parcours bancaire")
                .change("Utilisateurs virtuels", 20, 40)
                .change("Durée (s)", 60, 60)
                .description("Scénario modifié"));

        AuditLog entry = written();
        assertThat(entry.getUserId()).isEqualTo("user-123");
        assertThat(entry.getUsername()).isEqualTo("alice");
        assertThat(entry.getActorType()).isEqualTo("USER");
        assertThat(entry.getIp()).isEqualTo("192.168.1.10");
        assertThat(entry.getUserAgent()).contains("Chrome/141");
        assertThat(entry.getTargetType()).isEqualTo("SCENARIO");
        assertThat(entry.getTargetLabel()).isEqualTo("Parcours bancaire");
        // Seuls les champs reellement modifies sont gardes.
        assertThat(entry.getChanges()).isEqualTo("[{\"label\":\"Utilisateurs virtuels\",\"before\":\"20\",\"after\":\"40\"}]");
        assertThat(entry.getResult()).isEqualTo(AuditResult.SUCCESS);
    }

    @Test
    void record_shortForm_stillWorks_withoutAuthentication() {
        service.record(AuditAction.READ, AuditModule.DASHBOARD, AuditResult.SUCCESS, "desc");
        AuditLog entry = written();
        assertThat(entry.getUserId()).isNull();
        assertThat(entry.getIp()).isNull();
        assertThat(entry.getDescription()).isEqualTo("desc");
    }

    @Test
    void record_nonJwtAuthentication_userIsNull() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("someone", "n/a", "ROLE_SUPER_ADMIN"));
        service.record(AuditAction.READ, AuditModule.DASHBOARD, AuditResult.SUCCESS, "desc");
        assertThat(written().getUserId()).isNull();
    }

    @Test
    void record_writerThrows_isSwallowedAndNeverPropagatesToCaller() {
        doThrow(new RuntimeException("boom")).when(auditLogWriter).write(any());
        assertThatCode(() -> service.record(AuditEvent.of(AuditAction.DELETE, AuditModule.STEP).description("x"))).doesNotThrowAnyException();
    }

    @Test
    void record_longDescription_isTruncatedTo500Characters() {
        service.record(AuditEvent.of(AuditAction.UPDATE, AuditModule.APPLICATION).description("x".repeat(600)));
        assertThat(written().getDescription()).hasSize(500).endsWith("…");
    }

    // ---------------------------------------------------------------- consultations

    @Test
    void consultation_isNotRecorded_byDefault_butExportsAre() {
        when(settings.logReads()).thenReturn(false);
        service.record(AuditEvent.of(AuditAction.VIEW_REPORT, AuditModule.EXECUTION).consultation().description("Rapport consulté"));
        verify(auditLogWriter, never()).write(any());

        service.record(AuditEvent.of(AuditAction.EXPORT_CSV, AuditModule.EXECUTION).description("Rapport exporté au format CSV"));
        assertThat(written().getAction()).isEqualTo(AuditAction.EXPORT_CSV);
    }

    @Test
    void consultation_isRecorded_whenTheSettingIsOn() {
        when(settings.logReads()).thenReturn(true);
        service.record(AuditEvent.of(AuditAction.VIEW_REPORT, AuditModule.EXECUTION).consultation().description("Rapport consulté"));
        assertThat(written().getAction()).isEqualTo(AuditAction.VIEW_REPORT);
    }

    // ---------------------------------------------------------------- doublons

    @Test
    void identicalAction_inTheSameSecond_isRecordedOnce_butADifferentOneIsNot() {
        authenticateAs("user-1", "alice");
        AuditEvent same = AuditEvent.of(AuditAction.UPDATE, AuditModule.APPLICATION).target(AuditTargetType.APPLICATION, "a1", "Banking").description("Application modifiée");
        service.record(same);
        service.record(AuditEvent.of(AuditAction.UPDATE, AuditModule.APPLICATION).target(AuditTargetType.APPLICATION, "a1", "Banking").description("Application modifiée"));
        service.record(AuditEvent.of(AuditAction.UPDATE, AuditModule.APPLICATION).target(AuditTargetType.APPLICATION, "a2", "Autre").description("Application modifiée"));
        verify(auditLogWriter, times(2)).write(any());
    }

    // ---------------------------------------------------------------- secrets

    @Test
    void secrets_areMasked_inDescriptionLabelsAndChanges() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.c2lnbmF0dXJlLWRlLXRlc3Q";
        service.record(AuditEvent.of(AuditAction.UPDATE, AuditModule.APPLICATION)
                .target(AuditTargetType.APPLICATION, "a1", "App token=abc123")
                .change("Configuration", "password=Old-Secret-1", "Bearer " + jwt)
                .description("Connexion avec mot de passe : Hunter-2, secret=s3cr3t, Authorization: Basic Zm9v"));
        AuditLog entry = written();
        String all = entry.getDescription() + entry.getTargetLabel() + entry.getChanges();
        assertThat(all).doesNotContain("Hunter-2", "s3cr3t", "abc123", "Old-Secret-1", jwt, "Zm9v");
        assertThat(entry.getDescription()).contains("•••");
    }

    // ---------------------------------------------------------------- acteur Systeme / import

    @Test
    void systemAction_hasSystemActorAndSource_neverTheCurrentUser() {
        authenticateAs("user-1", "alice");
        service.record(AuditEvent.of(AuditAction.TRIGGER, AuditModule.SCHEDULING)
                .target(AuditTargetType.SCHEDULE, "s1", "Test nocturne").system("Planification : Test nocturne").description("Exécution lancée"));
        AuditLog entry = written();
        assertThat(entry.getActorType()).isEqualTo("SYSTEM");
        assertThat(entry.getActorSource()).isEqualTo("Planification : Test nocturne");
        assertThat(entry.getUserId()).isNull();
        assertThat(entry.getUsername()).isNull();
    }

    @Test
    void importedSecurityEvent_keepsItsOwnDateActorIpAndExternalId() {
        Instant when = Instant.parse("2026-10-07T21:15:00Z");
        service.record(AuditEvent.of(AuditAction.LOGIN_FAILED, AuditModule.SECURITY).result(AuditResult.FAILURE)
                .description("Mot de passe incorrect (nom saisi : fati@gmail.com)")
                .imported("kc-1", when, null, "fati@gmail.com", "172.17.0.1", "Authentification"));
        AuditLog entry = written();
        assertThat(entry.getDate()).isEqualTo(when);
        assertThat(entry.getUsername()).isEqualTo("fati@gmail.com");
        assertThat(entry.getIp()).isEqualTo("172.17.0.1");
        assertThat(entry.getExternalId()).isEqualTo("kc-1");
        assertThat(entry.getActorType()).isEqualTo("USER");
    }

    // ---------------------------------------------------------------- IP

    @Test
    void forwardedFor_isRead_onlyBehindATrustedProxy() {
        MockHttpServletRequest viaProxy = request("10.0.0.5");
        viaProxy.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.5");
        service.record(AuditEvent.of(AuditAction.UPDATE, AuditModule.APPLICATION).description("a"));
        assertThat(written().getIp()).isEqualTo("203.0.113.7");

        RequestIpResolver resolver = new RequestIpResolver(List.of("10.0.0.5"));
        MockHttpServletRequest forged = new MockHttpServletRequest();
        forged.setRemoteAddr("198.51.100.9");
        forged.addHeader("X-Forwarded-For", "1.2.3.4");
        assertThat(resolver.resolve(forged)).isEqualTo("198.51.100.9");
    }

    // ---------------------------------------------------------------- lecture

    @Test
    void getById_unknownId_throwsResourceNotFound() {
        UUID id = UUID.randomUUID();
        when(auditLogRepository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getById(id)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void search_returnsFrenchLabels_readablePhrase_changes_andLocalhost() {
        AuditLog entry = AuditLog.builder().id(UUID.randomUUID()).actorType("USER").username("alice")
                .action(AuditAction.ROLE_CHANGE).module(AuditModule.SECURITY).result(AuditResult.SUCCESS).date(Instant.now())
                .ip("0:0:0:0:0:0:0:1").targetType("USER").targetId("u-2").targetLabel("lina")
                .changes("[{\"label\":\"Rôle\",\"before\":\"Lecteur\",\"after\":\"Administrateur\"}]").description("Rôle modifié").build();
        when(auditLogRepository.findAll(org.mockito.ArgumentMatchers.<Specification<AuditLog>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entry), PageRequest.of(0, 20), 1));

        PagedResponse<AuditLogResponse> result = service.search(new AuditLogFilterRequest(null, null, null, null, null, null, null), 0, 20);

        AuditLogResponse row = result.content().get(0);
        assertThat(row.actionLabel()).isEqualTo("Changement de rôle");
        assertThat(row.categoryLabel()).isEqualTo("Sécurité");
        assertThat(row.resultLabel()).isEqualTo("Réussi");
        assertThat(row.summary()).isEqualTo("Changement de rôle du compte « lina »");
        assertThat(row.changes()).containsExactly(new AuditChange("Rôle", "Lecteur", "Administrateur"));
        assertThat(row.ipLabel()).isEqualTo("localhost");
        assertThat(row.targetExists()).isTrue();
        assertThat(row.legacy()).isFalse();
    }

    @Test
    void legacyRow_reportView_isLabelledAsAConsultation_withoutExceptionNames() {
        AuditLog legacy = AuditLog.builder().id(UUID.randomUUID()).actorType("USER").username("alice")
                .action(AuditAction.EXPORT_REPORT).module(AuditModule.EXECUTION).result(AuditResult.FAILURE).date(Instant.now())
                .description("Report view failed for execution 42: IllegalStateException").build();
        when(auditLogRepository.findById(legacy.getId())).thenReturn(Optional.of(legacy));

        AuditLogResponse row = service.getById(legacy.getId());

        assertThat(row.actionLabel()).isEqualTo("Consultation du rapport");
        assertThat(row.legacy()).isTrue();
        assertThat(row.detail()).isEqualTo("Report view failed for execution 42");
    }

    // ---------------------------------------------------------------- export

    @Test
    void exportCsv_isInFrench_semicolonSeparated_withBom_escaped_andWithoutSecrets() {
        AuditLog entry = AuditLog.builder().id(UUID.randomUUID()).actorType("SYSTEM").actorSource("Planification : Nuit")
                .action(AuditAction.TRIGGER).module(AuditModule.SCHEDULING).date(Instant.parse("2026-01-01T00:00:00Z"))
                .ip("127.0.0.1").result(AuditResult.FAILURE).targetType("SCHEDULE").targetId("s1").targetLabel("Nuit")
                .description("Contient ; un point-virgule \"et des guillemets\" token=abc").build();
        when(auditLogRepository.findAll(org.mockito.ArgumentMatchers.<Specification<AuditLog>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entry)));

        String csv = service.exportCsv(new AuditLogFilterRequest(null, null, null, null, null, null, null));

        assertThat(csv).startsWith("﻿Date;Utilisateur;Catégorie;Action;Détail;Avant → après;Résultat;Adresse IP\r\n");
        assertThat(csv).contains("Système (Planification : Nuit);Planification;Déclenchement de la planification « Nuit »;");
        assertThat(csv).contains("\"Contient ; un point-virgule \"\"et des guillemets\"\" token=•••\"");
        assertThat(csv).contains(";Échec;localhost\r\n");
        assertThat(csv).doesNotContain("abc", "TRIGGER", "SCHEDULING", "FAILURE");
    }

    @Test
    void exportCsv_noRows_returnsOnlyHeader() {
        when(auditLogRepository.findAll(org.mockito.ArgumentMatchers.<Specification<AuditLog>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        assertThat(service.exportCsv(new AuditLogFilterRequest(null, null, null, null, null, null, null)))
                .isEqualTo("﻿Date;Utilisateur;Catégorie;Action;Détail;Avant → après;Résultat;Adresse IP\r\n");
    }

    @Test
    void failureReasons_areFrench_andNeverExceptionClassNames() {
        service.record(AuditEvent.of(AuditAction.CREATE, AuditModule.USER).description("Création refusée")
                .failed(new ConflictException("Ce nom d'utilisateur existe déjà.")));
        AuditLog entry = written();
        assertThat(entry.getResult()).isEqualTo(AuditResult.FAILURE);
        assertThat(entry.getDescription()).isEqualTo("Création refusée — Ce nom d'utilisateur existe déjà.");

        AuditLogServiceImplUnitTest other = new AuditLogServiceImplUnitTest();
        other.setUp();
        other.service.record(AuditEvent.of(AuditAction.CREATE, AuditModule.USER).failed(new IllegalStateException("détail interne")));
        assertThat(other.written().getDescription()).isEqualTo("Erreur inattendue.").doesNotContain("IllegalStateException");
    }
}
