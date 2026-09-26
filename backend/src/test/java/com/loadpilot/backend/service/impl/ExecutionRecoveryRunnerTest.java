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

/**
 * P0-B (prompt section 5) - preuve REELLE de la strategie de recuperation au
 * demarrage : jamais une reprise fictive (voir Javadoc d'
 * ExecutionRecoveryRunner), une transition honnete vers FAILED avec une
 * raison explicite pour toute Execution retrouvee QUEUED/RUNNING.
 *
 * Invoque directement runner.run(null) plutot que de redemarrer le contexte
 * Spring (couteux, et le ApplicationRunner ne s'execute de toute facon
 * qu'une seule fois par cycle de vie de contexte) - teste la VRAIE logique
 * du composant, pas un mecanisme de demarrage difficile a re-declencher a
 * la demande dans un test.
 */
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

        // Audit reel de la recuperation (jamais silencieuse) - au moins une
        // entree EXECUTE/FAILURE mentionnant chacune des deux executions.
        boolean auditedQueued = auditLogRepository.findAll().stream().anyMatch(a ->
                a.getAction() == AuditAction.EXECUTE && a.getModule() == AuditModule.EXECUTION
                        && a.getResult() == AuditResult.FAILURE
                        && a.getDescription() != null && a.getDescription().contains(queuedOrphan.getId().toString()));
        boolean auditedRunning = auditLogRepository.findAll().stream().anyMatch(a ->
                a.getAction() == AuditAction.EXECUTE && a.getModule() == AuditModule.EXECUTION
                        && a.getResult() == AuditResult.FAILURE
                        && a.getDescription() != null && a.getDescription().contains(runningOrphan.getId().toString()));
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

        // Deja FAILED (terminal) apres le premier run : le second run ne le
        // retrouve plus jamais (requete WHERE status IN (QUEUED,RUNNING)) et
        // ne le touche donc pas une seconde fois.
        assertThat(afterSecondRun.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(afterSecondRun.getFinishedAt()).isEqualTo(finishedAtAfterFirstRun);
    }
}
