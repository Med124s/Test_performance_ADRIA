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
import com.loadpilot.backend.repository.ExecutionStepResultRepository;
import com.loadpilot.backend.repository.ScenarioRepository;
import com.loadpilot.backend.repository.StepRepository;
import com.loadpilot.backend.service.ExecutionService;
import com.loadpilot.backend.service.execution.ExecutionEngine;
import com.loadpilot.backend.service.execution.RunningExecutionHandle;
import com.loadpilot.backend.service.execution.StepExecutionSpec;
import com.loadpilot.backend.service.execution.StepOutcome;
import com.loadpilot.backend.service.execution.VariableSources;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Interruption en plein test : les resultats deja ecrits en base pendant l'execution (ResultStream)
 * survivent et restent consultables - apres une exception inattendue du moteur, comme apres un arret
 * brutal du processus (redemarrage simule : ExecutionRecoveryRunner sur une execution restee « En cours »).
 *
 * Moteur simule : il envoie ses resultats au flux d'ecriture de l'execution, exactement comme
 * HttpClientExecutionEngine, puis echoue ou reste bloque.
 */
@SpringBootTest
@ActiveProfiles("test")
class ExecutionInterruptionTest {

    @Autowired private ExecutionService executionService;
    @Autowired private ExecutionServiceImpl executionServiceImpl;
    @Autowired private ExecutionRepository executionRepository;
    @Autowired private ExecutionStepResultRepository resultRepository;
    @Autowired private ScenarioRepository scenarioRepository;
    @Autowired private StepRepository stepRepository;
    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private AppUserRepository appUserRepository;
    @Autowired private ExecutionRecoveryRunner recoveryRunner;
    @MockBean private JwtDecoder jwtDecoder;
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

    /** Envoie "count" resultats au flux de l'execution (1 echec sur 5), comme le moteur reel. */
    private static void produce(org.mockito.invocation.InvocationOnMock invocation, int count) {
        List<StepExecutionSpec> steps = invocation.getArgument(0);
        RunningExecutionHandle handle = invocation.getArgument(4);
        assertThat(handle.resultStream()).as("flux d'ecriture branche par le service").isNotNull();
        for (int i = 0; i < count; i++) {
            boolean success = i % 5 != 0;
            handle.resultStream().accept(new StepOutcome(steps.get(0).stepId(), success ? 200 : 500, 20 + i, success,
                    success ? null : "Reponse HTTP 500 (attendu 2xx-3xx)", Instant.now(), 0, i, "http://localhost:1/"));
        }
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(50);
        assertThat(condition.getAsBoolean()).as(what).isTrue();
    }

    private Execution reload(UUID id) {
        return executionRepository.findById(id).orElseThrow();
    }

    @Test
    void unexpectedEngineException_theResultsAlreadyObtainedAreKeptAndVisible() throws Exception {
        Scenario scenario = scenarioWithOneStep("crash-exception");
        when(executionEngine.execute(any(), any(), any(), any(), any(), any(VariableSources.class), any())).thenAnswer(invocation -> {
            produce(invocation, 250);
            throw new IllegalStateException("panne simulée en plein test");
        });

        UUID id = UUID.fromString(executionService.execute(new ExecutionRequest(scenario.getId()), null).id());
        await(() -> !reload(id).getStatus().isActive(), "execution terminee");

        Execution execution = reload(id);
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(execution.getErrorMessage()).isEqualTo(
                "Erreur inattendue du serveur LoadPilot (IllegalStateException) : 250 résultat(s) obtenu(s) avant l'erreur conservé(s).");
        assertThat(resultRepository.countAllByExecutionId(id)).isEqualTo(250);
        assertThat(execution.getFailedSteps()).isEqualTo(50);
        assertThat(execution.getSuccessfulSteps()).isEqualTo(200);
        // Consultables comme ceux de toute execution.
        assertThat(executionServiceImpl.getById(id, true).results()).hasSize(250);
    }

    @Test
    void processKilledDuringTheTest_resultsWrittenBeforeTheCrashSurviveTheRestart() throws Exception {
        Scenario scenario = scenarioWithOneStep("crash-kill");
        CountDownLatch neverFinishes = new CountDownLatch(1);
        when(executionEngine.execute(any(), any(), any(), any(), any(), any(VariableSources.class), any())).thenAnswer(invocation -> {
            produce(invocation, 330);
            // Le processus « meurt » ici : plus aucune finalisation (le moteur ne rend jamais la main).
            neverFinishes.await(60, TimeUnit.SECONDS);
            return new com.loadpilot.backend.service.execution.ScenarioExecutionOutcome(List.of(), ExecutionStatus.FAILED, null);
        });

        UUID id = UUID.fromString(executionService.execute(new ExecutionRequest(scenario.getId()), null).id());
        // Ecrits PENDANT le test, sans attendre sa fin : lots de 100, puis le reste apres une seconde.
        await(() -> resultRepository.countAllByExecutionId(id) == 330, "resultats ecrits pendant le test");
        assertThat(reload(id).getStatus()).isEqualTo(ExecutionStatus.RUNNING);

        try {
            // Redemarrage du serveur : l'execution orpheline passe a « Échouée », ses resultats restent.
            recoveryRunner.run(null);
            Execution recovered = reload(id);
            assertThat(recovered.getStatus()).isEqualTo(ExecutionStatus.FAILED);
            assertThat(recovered.getErrorMessage()).isEqualTo("Backend restarted while execution was running "
                    + "(330 résultat(s) enregistré(s) avant l'interruption, conservé(s))");
            assertThat(recovered.getSuccessfulSteps() + recovered.getFailedSteps()).isEqualTo(330);
            assertThat(recovered.getFailedSteps()).isEqualTo(66);
            assertThat(resultRepository.countAllByExecutionId(id)).as("rien n'est supprime").isEqualTo(330);
            assertThat(executionServiceImpl.getById(id, true).results()).hasSize(330);
        } finally {
            neverFinishes.countDown();
        }
        // La tache « fantome » qui se reveille ensuite n'ecrase jamais le statut enregistre.
        Thread.sleep(500);
        assertThat(reload(id).getErrorMessage()).startsWith("Backend restarted while execution was running");
    }
}
