package com.loadpilot.backend.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Moteur reel contre un serveur HTTP local : les resultats apparaissent en base PENDANT le test (et non
 * seulement a sa fin), et une annulation en cours de route garde exactement les resultats ecrits.
 */
@SpringBootTest
@ActiveProfiles("test")
class ExecutionProgressiveWriteTest {

    private static HttpServer server;
    private static String baseUrl;

    @Autowired private ExecutionService executionService;
    @Autowired private ExecutionRepository executionRepository;
    @Autowired private ExecutionStepResultRepository resultRepository;
    @Autowired private ScenarioRepository scenarioRepository;
    @Autowired private StepRepository stepRepository;
    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private AppUserRepository appUserRepository;
    @MockBean private JwtDecoder jwtDecoder;

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", ex -> {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private Scenario scenario(int durationSeconds) {
        AppUser user = appUserRepository.saveAndFlush(AppUser.builder()
                .keycloakSubject(UUID.randomUUID().toString()).username("progressive-" + UUID.randomUUID()).enabled(true).build());
        Application app = applicationRepository.saveAndFlush(Application.builder()
                .name("progressive-app-" + UUID.randomUUID()).url(baseUrl).createdBy(user).build());
        Scenario scenario = scenarioRepository.saveAndFlush(Scenario.builder()
                .application(app).name("progressive-" + UUID.randomUUID()).status(ScenarioStatus.ACTIVE)
                .createdBy(user).virtualUsers(2).rampUpSeconds(0).thinkTimeMs(0).durationSeconds(durationSeconds).build());
        stepRepository.saveAndFlush(Step.builder().scenario(scenario).name("Accueil").method(HttpMethod.GET)
                .url("/").order(1).status(StepStatus.ACTIVE).expectedStatus(200).build());
        return scenario;
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(50);
        assertThat(condition.getAsBoolean()).as(what).isTrue();
    }

    private Execution reload(UUID id) {
        return executionRepository.findById(id).orElseThrow();
    }

    @Test
    void resultsAreInTheDatabaseWhileTheTestIsStillRunning_andAllOfThemAtTheEnd() throws Exception {
        UUID id = UUID.fromString(executionService.execute(new ExecutionRequest(scenario(4).getId()), null).id());

        await(() -> resultRepository.countAllByExecutionId(id) >= 100, "premier lot ecrit");
        assertThat(reload(id).getStatus()).as("encore en cours au moment de la lecture").isEqualTo(ExecutionStatus.RUNNING);

        await(() -> !reload(id).getStatus().isActive(), "execution terminee");
        Execution done = reload(id);
        assertThat(done.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        long rows = resultRepository.countAllByExecutionId(id);
        assertThat(rows).isGreaterThan(100);
        assertThat(done.getSuccessfulSteps() + done.getFailedSteps()).as("compteurs = lignes en base").isEqualTo(rows);
    }

    @Test
    void cancelledMidway_keepsExactlyTheResultsWritten() throws Exception {
        UUID id = UUID.fromString(executionService.execute(new ExecutionRequest(scenario(60).getId()), null).id());
        await(() -> resultRepository.countAllByExecutionId(id) >= 200, "resultats ecrits pendant le test");

        executionService.cancel(id);
        await(() -> !reload(id).getStatus().isActive(), "execution arretee");
        Execution cancelled = reload(id);
        assertThat(cancelled.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
        long rows = resultRepository.countAllByExecutionId(id);
        assertThat(rows).isGreaterThanOrEqualTo(200);
        assertThat(cancelled.getSuccessfulSteps() + cancelled.getFailedSteps()).isEqualTo(rows);
    }
}
