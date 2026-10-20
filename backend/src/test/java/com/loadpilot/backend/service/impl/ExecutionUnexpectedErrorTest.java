package com.loadpilot.backend.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.loadpilot.backend.dto.request.ExecutionRequest;
import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.entity.Scenario;
import com.loadpilot.backend.entity.Step;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.enums.HttpMethod;
import com.loadpilot.backend.enums.ScenarioStatus;
import com.loadpilot.backend.enums.StepStatus;
import com.loadpilot.backend.repository.AppUserRepository;
import com.loadpilot.backend.repository.ApplicationRepository;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.ScenarioRepository;
import com.loadpilot.backend.repository.StepRepository;
import com.loadpilot.backend.service.ExecutionService;
import com.loadpilot.backend.service.execution.ExecutionEngine;
import com.loadpilot.backend.service.execution.VariableSources;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Erreur inattendue pendant l'execution asynchrone (ExecutionServiceImpl.runAsync) : une Error comme
 * OutOfMemoryError n'est plus avalee par la tache lancee via submit() - elle est journalisee avec son
 * type, l'execution passe a « Échouée » en base (et ne reste plus « En cours » jusqu'au prochain
 * redemarrage), la place qu'elle occupait est liberee. Un statut de fin deja enregistre n'est jamais ecrase.
 */
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class ExecutionUnexpectedErrorTest {

    @Autowired private ExecutionService executionService;
    @Autowired private ExecutionTransactionHelper transactionHelper;
    @Autowired private ExecutionRepository executionRepository;
    @Autowired private ScenarioRepository scenarioRepository;
    @Autowired private StepRepository stepRepository;
    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private AppUserRepository appUserRepository;
    @MockBean private JwtDecoder jwtDecoder;
    /** Moteur simule : leve l'erreur au milieu du « test de charge ». */
    @MockBean private ExecutionEngine executionEngine;

    private Scenario scenarioWithOneStep(String tag) {
        AppUser user = appUserRepository.saveAndFlush(AppUser.builder()
                .keycloakSubject(UUID.randomUUID().toString()).username(tag + "-" + UUID.randomUUID()).enabled(true).build());
        Application app = applicationRepository.saveAndFlush(Application.builder()
                .name(tag + "-app-" + UUID.randomUUID()).url("http://localhost:1").createdBy(user).build());
        Scenario scenario = scenarioRepository.saveAndFlush(Scenario.builder()
                .application(app).name(tag + "-scenario-" + UUID.randomUUID()).status(ScenarioStatus.ACTIVE)
                .createdBy(user).virtualUsers(1).rampUpSeconds(0).thinkTimeMs(0).build());
        stepRepository.saveAndFlush(Step.builder().scenario(scenario).name("Accueil").method(HttpMethod.GET)
                .url("/").order(1).status(StepStatus.ACTIVE).build());
        return scenario;
    }

    private void engineThrows(Throwable error) {
        when(executionEngine.execute(any(), any(), any(), any(), any(), any(VariableSources.class), any())).thenThrow(error);
    }

    /** Statut de fin enregistre par la tache asynchrone (attente bornee). */
    private Execution awaitTerminal(String executionId) throws InterruptedException {
        UUID id = UUID.fromString(executionId);
        long deadline = System.nanoTime() + 15_000_000_000L;
        Execution execution = executionRepository.findById(id).orElseThrow();
        while (execution.getStatus().isActive() && System.nanoTime() < deadline) {
            Thread.sleep(50);
            execution = executionRepository.findById(id).orElseThrow();
        }
        return execution;
    }

    @Test
    void outOfMemoryError_isLoggedWithItsType_andTheExecutionIsMarkedFailed(CapturedOutput output) throws Exception {
        Scenario scenario = scenarioWithOneStep("oom");
        engineThrows(new OutOfMemoryError("Java heap space (simulée par le test)"));

        String executionId = executionService.execute(new ExecutionRequest(scenario.getId()), null).id();
        Execution execution = awaitTerminal(executionId);

        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(execution.getErrorMessage()).startsWith("Mémoire du backend insuffisante (OutOfMemoryError)");
        assertThat(execution.getFinishedAt()).isNotNull();
        assertThat(output.getAll()).contains("Echec inattendu de l'execution asynchrone " + executionId + " : java.lang.OutOfMemoryError");

        // Place liberee : le meme scenario peut etre relance (la limite de tests simultanes n'est pas consommee).
        engineThrows(new OutOfMemoryError("encore"));
        String second = executionService.execute(new ExecutionRequest(scenario.getId()), null).id();
        assertThat(awaitTerminal(second).getStatus()).isEqualTo(ExecutionStatus.FAILED);
    }

    @Test
    void unexpectedRuntimeException_alsoMarksTheExecutionFailed_insteadOfLeavingItRunning() throws Exception {
        Scenario scenario = scenarioWithOneStep("rte");
        engineThrows(new IllegalStateException("panne simulée"));

        Execution execution = awaitTerminal(executionService.execute(new ExecutionRequest(scenario.getId()), null).id());

        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(execution.getErrorMessage())
                .isEqualTo("Erreur inattendue du serveur LoadPilot (IllegalStateException) : résultats non enregistrés.");
    }

    @Test
    void markFailedIfActive_neverOverwritesAnAlreadyRecordedFinalStatus() {
        Scenario scenario = scenarioWithOneStep("done");
        Execution done = executionRepository.saveAndFlush(Execution.builder()
                .scenario(scenario).startedAt(Instant.now()).finishedAt(Instant.now()).status(ExecutionStatus.SUCCESS)
                .virtualUsers(1).rampUpSeconds(0).totalSteps(1).successfulSteps(1).failedSteps(0).build());

        assertThat(transactionHelper.markFailedIfActive(done.getId(), "ne doit pas apparaître")).isFalse();

        Execution reloaded = executionRepository.findById(done.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(reloaded.getErrorMessage()).isNull();
    }
}
