package com.loadpilot.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.dto.request.StepTestRunRequest;
import com.loadpilot.backend.dto.response.TestRunResponse;
import com.loadpilot.backend.service.execution.StepExecutionSpec;
import com.loadpilot.backend.service.execution.StepTestRunner;
import com.loadpilot.backend.service.execution.TestRunPlan;
import com.loadpilot.backend.service.impl.TestRunPlanner;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/**
 * Tests de verification detailles (type Postman) : 1 utilisateur virtuel, 1 passage, requete
 * envoyee + reponse recue par etape. Aucune Execution ni Metric creee, rien de journalise.
 *
 * ?stream=true : meme test, mais diffuse en direct au format NDJSON (une ligne JSON par
 * evenement : started, auth, step-started, step, done) pour afficher l'etape en cours.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Tests detailles", description = "Verification d'un scenario ou d'une etape, sans historique ni metriques")
public class TestRunController {

    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson;charset=UTF-8");

    private final TestRunPlanner planner;
    private final StepTestRunner runner;
    private final ExecutorService loadTestExecutor;
    private final ObjectMapper objectMapper;

    @PostMapping("/api/scenarios/{id}/test-run")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Tester un scenario en detail",
            description = "1 utilisateur virtuel, 1 passage, memes regles que le moteur. Secrets masques, corps limite a 100 Ko, delai global 60 s.")
    @ApiResponse(responseCode = "404", description = "Scenario introuvable")
    @ApiResponse(responseCode = "409", description = "Scenario sans etape ou archive")
    public TestRunResponse testScenario(@PathVariable UUID id,
            @RequestBody(required = false) @jakarta.validation.Valid com.loadpilot.backend.dto.request.ScenarioFullRequest draft,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Integer upToStep) {
        // Corps fourni : version NON enregistree (page de modification) ; sinon le scenario enregistre.
        return runner.run(draft != null ? planner.forDraft(id, draft, upToStep) : planner.forScenario(id), StepTestRunner.Listener.NONE);
    }

    @PostMapping(value = "/api/scenarios/{id}/test-run", params = "stream=true")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Tester un scenario en detail (progression en direct, NDJSON)")
    public ResponseEntity<ResponseBodyEmitter> testScenarioStream(@PathVariable UUID id,
            @RequestBody(required = false) @jakarta.validation.Valid com.loadpilot.backend.dto.request.ScenarioFullRequest draft,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Integer upToStep) {
        // Validation (404/409) AVANT l'ouverture du flux : erreurs JSON habituelles.
        return stream(draft != null ? planner.forDraft(id, draft, upToStep) : planner.forScenario(id));
    }

    @PostMapping("/api/steps/{id}/test-run")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Tester une seule etape en detail",
            description = "Reutilise les variables fournies (ex. {\"variables\":{\"transferId\":\"...\"}}) ; authentification de l'application incluse.")
    @ApiResponse(responseCode = "404", description = "Etape introuvable")
    public TestRunResponse testStep(@PathVariable UUID id, @RequestBody(required = false) StepTestRunRequest request) {
        return runner.run(planner.forStep(id, request == null ? null : request.variables()), StepTestRunner.Listener.NONE);
    }

    @PostMapping(value = "/api/steps/{id}/test-run", params = "stream=true")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    @Operation(summary = "Tester une seule etape en detail (progression en direct, NDJSON)")
    public ResponseEntity<ResponseBodyEmitter> testStepStream(@PathVariable UUID id,
            @RequestBody(required = false) StepTestRunRequest request) {
        return stream(planner.forStep(id, request == null ? null : request.variables()));
    }

    private ResponseEntity<ResponseBodyEmitter> stream(TestRunPlan plan) {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(
                TimeUnit.SECONDS.toMillis(runner.globalTimeoutSeconds() + 30));
        loadTestExecutor.submit(() -> {
            try {
                Map<String, Object> started = event("started");
                started.put("totalSteps", plan.steps().size());
                started.put("steps", plan.steps().stream().map(s -> Map.of(
                        "stepId", String.valueOf(s.stepId()), "name", s.name(),
                        "method", s.method().name(), "url", s.url())).toList());
                send(emitter, started);
                TestRunResponse result = runner.run(plan, new StepTestRunner.Listener() {
                    @Override
                    public void authenticated(TestRunResponse.AuthResult auth) {
                        Map<String, Object> e = event("auth");
                        e.put("auth", auth);
                        send(emitter, e);
                    }

                    @Override
                    public void stepStarted(int index, StepExecutionSpec step) {
                        Map<String, Object> e = event("step-started");
                        e.put("index", index);
                        e.put("name", step.name());
                        send(emitter, e);
                    }

                    @Override
                    public void stepFinished(TestRunResponse.StepResult result) {
                        Map<String, Object> e = event("step");
                        e.put("step", result);
                        send(emitter, e);
                    }
                });
                Map<String, Object> done = event("done");
                done.put("result", result);
                send(emitter, done);
                emitter.complete();
            } catch (RuntimeException e) {
                // Client parti ou erreur inattendue : on ferme simplement le flux (rien journalise).
                emitter.completeWithError(e);
            }
        });
        return ResponseEntity.ok().contentType(NDJSON).body(emitter);
    }

    private static Map<String, Object> event(String type) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        return event;
    }

    private void send(ResponseBodyEmitter emitter, Map<String, Object> event) {
        try {
            emitter.send(objectMapper.writeValueAsString(event) + "\n", NDJSON);
        } catch (IOException e) {
            throw new IllegalStateException("Flux de test interrompu", e);
        }
    }
}
