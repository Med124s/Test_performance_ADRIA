package com.loadpilot.backend.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.entity.Scenario;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.enums.ScenarioStatus;
import com.loadpilot.backend.repository.ApplicationRepository;
import com.loadpilot.backend.repository.AppUserRepository;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.ScenarioRepository;
import com.loadpilot.backend.service.execution.CancellationAttempt;
import com.loadpilot.backend.service.execution.CancellationOutcome;
import com.loadpilot.backend.service.execution.ScenarioExecutionOutcome;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;

/** Changements de statut sous verrou : une annulation n'ecrase jamais un statut final deja enregistre. */
@SpringBootTest
@ActiveProfiles("test")
class ExecutionTransactionHelperRaceTest {

    @Autowired
    private ExecutionTransactionHelper transactionHelper;
    @Autowired
    private ExecutionRepository executionRepository;
    @Autowired
    private ScenarioRepository scenarioRepository;
    @Autowired
    private ApplicationRepository applicationRepository;
    @Autowired
    private AppUserRepository appUserRepository;
    @MockBean
    private JwtDecoder jwtDecoder;

    private Execution createExecution(ExecutionStatus status) {
        AppUser user = appUserRepository.saveAndFlush(AppUser.builder()
                .keycloakSubject(UUID.randomUUID().toString()).username("race-user-" + UUID.randomUUID()).enabled(true).build());
        Application app = applicationRepository.saveAndFlush(Application.builder()
                .name("race-app-" + UUID.randomUUID()).url("http://localhost:1").createdBy(user).build());
        Scenario scenario = scenarioRepository.saveAndFlush(Scenario.builder()
                .application(app).name("race-scenario-" + UUID.randomUUID()).status(ScenarioStatus.ACTIVE)
                .createdBy(user).virtualUsers(1).rampUpSeconds(0).thinkTimeMs(0).build());
        Execution execution = Execution.builder()
                .scenario(scenario).startedAt(Instant.now()).status(status)
                .virtualUsers(1).rampUpSeconds(0).totalSteps(1).successfulSteps(0).failedSteps(0)
                .build();
        return executionRepository.saveAndFlush(execution);
    }

    // ---- attemptCancellation : les 3 cas ----

    @Test
    void attemptCancellation_onQueued_transitionsDirectlyToCancelled() {
        Execution execution = createExecution(ExecutionStatus.QUEUED);

        CancellationAttempt attempt = transactionHelper.attemptCancellation(execution.getId());

        assertThat(attempt.outcome()).isEqualTo(CancellationOutcome.CANCELLED_WHILE_QUEUED);
        assertThat(attempt.execution().getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
        Execution reloaded = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(reloaded.getFinishedAt()).isNotNull();
    }

    @Test
    void attemptCancellation_onRunning_neverWritesToDatabase() {
        Execution execution = createExecution(ExecutionStatus.RUNNING);

        CancellationAttempt attempt = transactionHelper.attemptCancellation(execution.getId());

        assertThat(attempt.outcome()).isEqualTo(CancellationOutcome.CANCELLATION_REQUESTED_WHILE_RUNNING);
        // Aucune ecriture : seul finalizeExecution ecrit le statut final d'une execution demarree.
        Execution reloaded = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(reloaded.getFinishedAt()).isNull();
    }

    @Test
    void attemptCancellation_onAlreadySuccess_isRejectedAndNeverOverwritesTheRealOutcome() {
        Execution execution = createExecution(ExecutionStatus.SUCCESS);
        execution.setFinishedAt(Instant.now());
        execution.setDuration(1234L);
        executionRepository.saveAndFlush(execution);

        CancellationAttempt attempt = transactionHelper.attemptCancellation(execution.getId());

        assertThat(attempt.outcome()).isEqualTo(CancellationOutcome.ALREADY_TERMINAL);
        Execution reloaded = executionRepository.findById(execution.getId()).orElseThrow();
        // Un SUCCESS n'est jamais remplace par CANCELLED.
        assertThat(reloaded.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(reloaded.getDuration()).isEqualTo(1234L);
    }

    @Test
    void attemptCancellation_calledTwiceOnSameQueuedExecution_isIdempotent_secondCallSeesAlreadyTerminal() {
        Execution execution = createExecution(ExecutionStatus.QUEUED);

        CancellationAttempt first = transactionHelper.attemptCancellation(execution.getId());
        CancellationAttempt second = transactionHelper.attemptCancellation(execution.getId());

        assertThat(first.outcome()).isEqualTo(CancellationOutcome.CANCELLED_WHILE_QUEUED);
        assertThat(second.outcome()).isEqualTo(CancellationOutcome.ALREADY_TERMINAL);
        assertThat(second.execution().getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
        // Un seul etat final, coherent.
        Execution reloaded = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
    }

    // ---- markRunning : aucune requete pour une execution deja annulee ----

    @Test
    void markRunning_onQueued_transitionsAndReturnsTrue() {
        Execution execution = createExecution(ExecutionStatus.QUEUED);

        boolean transitioned = transactionHelper.markRunning(execution.getId());

        assertThat(transitioned).isTrue();
        assertThat(executionRepository.findById(execution.getId()).orElseThrow().getStatus())
                .isEqualTo(ExecutionStatus.RUNNING);
    }

    @Test
    void markRunning_onAlreadyCancelled_returnsFalseAndNeverOverwrites() {
        Execution execution = createExecution(ExecutionStatus.CANCELLED);

        // Un cancel() est passe (QUEUED -> CANCELLED) avant que runAsync n'appelle markRunning().
        boolean transitioned = transactionHelper.markRunning(execution.getId());

        assertThat(transitioned).isFalse();
        // false : le moteur n'est pas lance ; ici, le statut reste CANCELLED.
        assertThat(executionRepository.findById(execution.getId()).orElseThrow().getStatus())
                .isEqualTo(ExecutionStatus.CANCELLED);
    }

    // ---- finalizeExecution : un statut deja final n'est jamais remplace ----

    @Test
    void finalizeExecution_whenAlreadyCancelledConcurrently_leavesItUnchanged() {
        Execution execution = createExecution(ExecutionStatus.CANCELLED);
        execution.setFinishedAt(Instant.now());
        executionRepository.saveAndFlush(execution);

        // runAsync a compose un resultat FAILED puis appelle finalizeExecution : rien ne doit changer.
        ScenarioExecutionOutcome outcome = new ScenarioExecutionOutcome(List.of(), ExecutionStatus.FAILED, null);
        Execution result = transactionHelper.finalizeExecution(execution.getId(), outcome, false);

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
        Execution reloaded = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
    }

    @Test
    void finalizeExecution_onGenuinelyRunning_appliesTheRealOutcome() {
        Execution execution = createExecution(ExecutionStatus.RUNNING);

        ScenarioExecutionOutcome outcome = new ScenarioExecutionOutcome(List.of(), ExecutionStatus.SUCCESS, null);
        Execution result = transactionHelper.finalizeExecution(execution.getId(), outcome, false);

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.getFinishedAt()).isNotNull();
    }
}
