package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.entity.Scenario;
import com.loadpilot.backend.entity.Step;
import com.loadpilot.backend.exception.ConflictException;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.repository.ScenarioRepository;
import com.loadpilot.backend.repository.StepRepository;
import com.loadpilot.backend.service.auth.ApplicationAuthSupport;
import com.loadpilot.backend.service.execution.CsvDataSource;
import com.loadpilot.backend.service.execution.StepExecutionSpec;
import com.loadpilot.backend.service.execution.TestRunPlan;
import com.loadpilot.backend.dto.request.ScenarioFullRequest;
import com.loadpilot.backend.dto.request.StepRequest;
import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.exception.BadRequestException;
import com.loadpilot.backend.repository.ApplicationRepository;
import com.loadpilot.backend.service.execution.VariableSources;
import com.loadpilot.backend.service.validation.ScenarioVariables;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prepare un test detaille (voir StepTestRunner) dans une transaction courte en lecture
 * seule : memes etapes, meme ordre, meme authentification et meme CSV qu'une Execution
 * (voir ExecutionTransactionHelper.prepareAndStart) - mais rien n'est cree en base.
 */
@Service
@RequiredArgsConstructor
public class TestRunPlanner {

    private static final Sort STEP_ORDER = Sort.by(Sort.Direction.ASC, "order").and(Sort.by(Sort.Direction.ASC, "id"));

    private final ScenarioRepository scenarioRepository;
    private final ApplicationRepository applicationRepository;
    private final StepRepository stepRepository;
    private final ApplicationAuthSupport authSupport;

    @Transactional(readOnly = true)
    public TestRunPlan forScenario(UUID scenarioId) {
        Scenario scenario = scenarioRepository.findById(scenarioId)
                .orElseThrow(() -> new ResourceNotFoundException("Scenario introuvable : " + scenarioId));
        ensureNotArchived(scenario);
        List<Step> steps = stepRepository.findByScenarioId(scenarioId, STEP_ORDER);
        if (steps.isEmpty()) {
            throw new ConflictException("Le scénario ne possède aucune étape à tester.");
        }
        return plan(scenario, steps, null, Map.of());
    }

    /** Une seule etape, avec les variables fournies (ex. transferId capture lors d'un test precedent). */
    @Transactional(readOnly = true)
    public TestRunPlan forStep(UUID stepId, Map<String, String> providedVariables) {
        Step step = stepRepository.findById(stepId)
                .orElseThrow(() -> new ResourceNotFoundException("Etape introuvable : " + stepId));
        Scenario scenario = step.getScenario();
        ensureNotArchived(scenario);
        List<Step> allSteps = stepRepository.findByScenarioId(scenario.getId(), STEP_ORDER);
        return plan(scenario, allSteps, step.getId(), providedVariables == null ? Map.of() : providedVariables);
    }

    /**
     * Version NON enregistree d'un scenario (page de modification, « Tester cette étape ») : meme
     * plan que forScenario, construit depuis le corps recu - rien n'est persiste. upToStep (1..n) :
     * ne jouer que les etapes 1..upToStep (les precedentes fournissent leurs captures).
     */
    @Transactional(readOnly = true)
    public TestRunPlan forDraft(UUID scenarioId, ScenarioFullRequest draft, Integer upToStep) {
        Scenario scenario = scenarioRepository.findById(scenarioId)
                .orElseThrow(() -> new ResourceNotFoundException("Scenario introuvable : " + scenarioId));
        ensureNotArchived(scenario);
        Application application = applicationRepository.findById(draft.scenario().applicationId())
                .orElseThrow(() -> new ResourceNotFoundException("Application introuvable : " + draft.scenario().applicationId()));
        String csvProblem = CsvDataSource.validate(draft.scenario().csvData());
        if (csvProblem != null) throw new BadRequestException("CSV invalide : " + csvProblem);
        String variablesJson = draft.scenario().variables() != null
                ? ScenarioVariables.normalize(draft.scenario().variables()) : scenario.getVariables();
        int count = upToStep == null ? draft.steps().size() : Math.max(0, Math.min(upToStep, draft.steps().size()));
        if (count == 0) throw new ConflictException("Le scénario ne possède aucune étape à tester.");
        List<StepExecutionSpec> allSpecs = new ArrayList<>();
        for (ScenarioFullRequest.StepPayload payload : draft.steps()) {
            StepRequest s = payload.step();
            allSpecs.add(new StepExecutionSpec(payload.id(), s.name(), s.method(), s.url(), s.headers(), s.body(),
                    s.expectedStatus(), s.thinkTimeMs(), s.timeoutSeconds(), s.followRedirects(), s.assertionBodyContains(),
                    s.captureVariableName(), s.captureJsonPath(), s.pathParams(), s.queryParams(), s.captureSource(),
                    s.maxResponseTimeMs(), s.assertionJsonPath(), s.assertionJsonOperator(), s.assertionJsonValue()));
        }
        List<Map<String, String>> rows = CsvDataSource.parse(draft.scenario().csvData());
        return new TestRunPlan(scenarioId, application.getUrl(), authSupport.toSpec(application),
                allSpecs.subList(0, count), rows.isEmpty() ? Map.of() : rows.get(0), ScenarioVariables.parse(variablesJson),
                Map.of(), java.util.stream.IntStream.rangeClosed(1, count).boxed().toList(),
                new VariableSources(List.of(), null, Map.of(), allSpecs).captureStepByVariable(), com.loadpilot.backend.service.http.NetworkProfile.of(application));
    }

    /** onlyStepId null : toutes les etapes ; sinon cette seule etape (numerotee dans le scenario). */
    private TestRunPlan plan(Scenario scenario, List<Step> allSteps, UUID onlyStepId, Map<String, String> providedVariables) {
        List<StepExecutionSpec> allSpecs = allSteps.stream().map(TestRunPlanner::toSpec).toList();
        List<StepExecutionSpec> specs = new ArrayList<>();
        List<Integer> numbers = new ArrayList<>();
        for (int i = 0; i < allSpecs.size(); i++) {
            if (onlyStepId == null || onlyStepId.equals(allSpecs.get(i).stepId())) {
                specs.add(allSpecs.get(i));
                numbers.add(i + 1);
            }
        }
        // Utilisateur virtuel n°1 du moteur, premiere iteration : premiere ligne du CSV.
        List<Map<String, String>> rows = CsvDataSource.parse(scenario.getCsvData());
        Map<String, String> provided = new LinkedHashMap<>();
        providedVariables.forEach((name, value) -> {
            if (name != null && !name.isBlank() && value != null) provided.put(name, value);
        });
        return new TestRunPlan(scenario.getId(), scenario.getApplication().getUrl(),
                authSupport.toSpec(scenario.getApplication()), specs, rows.isEmpty() ? Map.of() : rows.get(0),
                ScenarioVariables.parse(scenario.getVariables()), provided, numbers,
                new VariableSources(List.of(), null, Map.of(), allSpecs).captureStepByVariable(), com.loadpilot.backend.service.http.NetworkProfile.of(scenario.getApplication()));
    }

    static StepExecutionSpec toSpec(Step s) {
        return new StepExecutionSpec(s.getId(), s.getName(), s.getMethod(), s.getUrl(),
                s.getHeaders(), s.getBody(), s.getExpectedStatus(), s.getThinkTimeMs(),
                s.getTimeoutSeconds(), s.getFollowRedirects(), s.getAssertionBodyContains(),
                s.getCaptureVariableName(), s.getCaptureJsonPath(), s.getPathParams(), s.getQueryParams(), s.getCaptureSource(),
                s.getMaxResponseTimeMs(), s.getAssertionJsonPath(), s.getAssertionJsonOperator(), s.getAssertionJsonValue());
    }

    private void ensureNotArchived(Scenario scenario) {
        if (scenario.isArchived() || scenario.getApplication().isArchived()) {
            throw new ConflictException(scenario.isArchived()
                    ? "Ce scénario est archivé : restaurez-le avant de le tester."
                    : "L'application de ce scénario est archivée : restaurez-la avant de tester.");
        }
    }
}
