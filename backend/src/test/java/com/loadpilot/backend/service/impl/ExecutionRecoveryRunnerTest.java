package com.loadpilot.backend.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.entity.Scenario;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.enums.ScenarioStatus;
import com.loadpilot.backend.repository.ApplicationRepository;
import com.loadpilot.backend.repository.AppUserRepository;
import com.loadpilot.backend.repository.AuditLogRepository;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.ScenarioRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;

/** Au demarrage, les executions restees en attente ou en cours passent a FAILED, avec leurs resultats conserves. */
@SpringBootTest
@ActiveProfiles("test")
class ExecutionRecoveryRunnerTest {

    @Autowired
    private ExecutionRecoveryRunner runner;
    @Autowired
    private ExecutionRepository executionRepository;
    @Autowired
    private ScenarioRepository scenarioRepository;
    @Autowired
    private ApplicationRepository applicationRepository;
    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @MockBean
    private JwtDecoder jwtDecoder;

    private Scenario createScenario(String prefix) {
        AppUser user = appUserRepository.saveAndFlush(AppUser.builder()
                .keycloakSubject(UUID.randomUUID().toString()).username(prefix + "-user").enabled(true).build());
        Application app = applicationRepository.saveAndFlush(Application.builder()
                .name(prefix + "-app").url("http://localhost:1").createdBy(user).build());
        Scenario scenario = Scenario.builder()
                .application(app).name(prefix + "-scenario").status(ScenarioStatus.ACTIVE).createdBy(user)
                .virtualUsers(3).rampUpSeconds(0).thinkTimeMs(0).build();
        return scenarioRepository.saveAndFlush(scenario);
    }

    private Execution createOrphan(Scenario scenario, ExecutionStatus status) {
        Execution execution = Execution.builder()
                .scenario(scenario)
                .startedAt(Instant.now().minusSeconds(30))
                .status(status)
                .virtualUsers(scenario.getVirtualUsers())
                .rampUpSeconds(0)
                .totalSteps(1)
                .successfulSteps(0)
                .failedSteps(0)
                .build();
        return executionRepository.saveAndFlush(execution);
    }

    @Test
    void run_transitionsOrphanedQueuedAndRunningExecutionsToFailedWithExplicitReason() {
        Scenario scenario = createScenario("recovery-" + UUID.randomUUID());
        Execution queuedOrphan = createOrphan(scenario, ExecutionStatus.QUEUED);
        Execution runningOrphan = createOrphan(scenario, ExecutionStatus.RUNNING);

        runner.run(null);

        Execution reloadedQueued = executionRepository.findById(queuedOrphan.getId()).orElseThrow();
        Execution reloadedRunning = executionRepository.findById(runningOrphan.getId()).orElseThrow();

        assertThat(reloadedQueued.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(reloadedQueued.getErrorMessage()).isEqualTo("Backend restarted while execution was running");
        assertThat(reloadedQueued.getFinishedAt()).isNotNull();
        assertThat(reloadedQueued.getDuration()).isNotNull();

        assertThat(reloadedRunning.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(reloadedRunning.getErrorMessage()).isEqualTo("Backend restarted while execution was running");

        // Une entree d'audit FINISH / FAILURE par execution, acteur Systeme (« Redemarrage du serveur »).
        boolean auditedQueued = auditLogRepository.findAll().stream().anyMatch(a ->
                a.getAction() == AuditAction.FINISH && a.getModule() == AuditModule.EXECUTION
                        && a.getResult() == AuditResult.FAILURE && "SYSTEM".equals(a.getActorType())
                        && queuedOrphan.getId().toString().equals(a.getTargetId()));
        boolean auditedRunning = auditLogRepository.findAll().stream().anyMatch(a ->
                a.getAction() == AuditAction.FINISH && a.getModule() == AuditModule.EXECUTION
                        && a.getResult() == AuditResult.FAILURE && "SYSTEM".equals(a.getActorType())
                        && runningOrphan.getId().toString().equals(a.getTargetId()));
        assertThat(auditedQueued).isTrue();
        assertThat(auditedRunning).isTrue();
    }

    @Test
    void run_neverTouchesAlreadyTerminalExecutions() {
        Scenario scenario = createScenario("recovery-terminal-" + UUID.randomUUID());
        Execution success = createOrphan(scenario, ExecutionStatus.SUCCESS);
        success.setFinishedAt(Instant.now());
        success.setDuration(500L);
        executionRepository.saveAndFlush(success);

        runner.run(null);

        Execution reloaded = executionRepository.findById(success.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(reloaded.getErrorMessage()).isNull();
    }

    @Test
    void run_isIdempotent_secondCallFindsNothingLeftToRecover() {
        Scenario scenario = createScenario("recovery-idempotent-" + UUID.randomUUID());
        Execution orphan = createOrphan(scenario, ExecutionStatus.RUNNING);

        runner.run(null);
        Execution afterFirstRun = executionRepository.findById(orphan.getId()).orElseThrow();
        Instant finishedAtAfterFirstRun = afterFirstRun.getFinishedAt();

        runner.run(null);
        Execution afterSecondRun = executionRepository.findById(orphan.getId()).orElseThrow();

        // Deja FAILED apres le premier passage : le second ne le modifie pas.
        assertThat(afterSecondRun.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(afterSecondRun.getFinishedAt()).isEqualTo(finishedAtAfterFirstRun);
    }
}
