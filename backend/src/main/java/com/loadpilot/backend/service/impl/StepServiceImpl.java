package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.dto.request.StepRequest;
import com.loadpilot.backend.dto.request.StepTestBatchRequest;
import com.loadpilot.backend.dto.response.StepResponse;
import com.loadpilot.backend.dto.response.StepTestResultResponse;
import com.loadpilot.backend.entity.Scenario;
import com.loadpilot.backend.entity.Step;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.enums.StepStatus;
import com.loadpilot.backend.exception.ConflictException;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.mapper.StepMapper;
import com.loadpilot.backend.repository.ScenarioRepository;
import com.loadpilot.backend.repository.StepRepository;
import com.loadpilot.backend.service.AuditLogService;
import com.loadpilot.backend.service.StepService;
import com.loadpilot.backend.service.execution.ExecutionEngine;
import com.loadpilot.backend.service.execution.StepExecutionSpec;
import com.loadpilot.backend.service.execution.StepOutcome;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ordre des Steps (voir Phase 8, point 12) : AUCUNE contrainte d'unicite
 * (scenario_id, step_order) en base. Justification : un futur ecran de
 * reordonnancement (drag&drop, comme deja prevu cote frontend existant)
 * doit pouvoir faire transiter plusieurs Steps par des etats intermediaires
 * avec des "order" temporairement dupliques (ex: echanger l'ordre de deux
 * etapes) - une contrainte unique bloquerait ou compliquerait inutilement
 * ces operations tout a fait legitimes. A la place, un ordre DETERMINISTE
 * est garanti par le tri : step_order ASC, puis id ASC en cas d'egalite -
 * jamais un ordre ambigu ou dependant de l'ordre d'insertion en base.
 */
@Service
@RequiredArgsConstructor
public class StepServiceImpl implements StepService {

    private static final Sort ORDER_ASC =
            Sort.by(Sort.Direction.ASC, "order").and(Sort.by(Sort.Direction.ASC, "id"));

    private final StepRepository stepRepository;
    private final ScenarioRepository scenarioRepository;
    private final StepMapper stepMapper;
    private final AuditLogService auditLogService;
    private final ExecutionEngine executionEngine;

    @Override
    @Transactional
    public StepResponse create(StepRequest request) {
        try {
            Scenario scenario = findScenarioOrThrow(request.scenarioId());

            Step step = stepMapper.toEntity(request);
            step.setScenario(scenario);
            // Choix declaratif de l'utilisateur, vrai des la creation - voir
            // StepStatus (different d'un resultat d'execution invente).
            // Passage produit reel (2026-09-30) : desormais reellement
            // honore par le moteur d'execution (voir
            // ExecutionTransactionHelper) - null = ACTIVE par defaut.
            step.setStatus(request.status() != null ? request.status() : StepStatus.ACTIVE);

            Step saved = stepRepository.saveAndFlush(step);
            auditLogService.record(AuditAction.CREATE, AuditModule.STEP, AuditResult.SUCCESS,
                    "Step created: " + saved.getName());
            return stepMapper.toResponse(saved);
        } catch (RuntimeException e) {
            auditLogService.record(AuditAction.CREATE, AuditModule.STEP, AuditResult.FAILURE,
                    "Step creation failed: " + e.getClass().getSimpleName());
            throw e;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public StepResponse getById(UUID id) {
        return stepMapper.toResponse(findOrThrow(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<StepResponse> list() {
        return stepMapper.toResponseList(stepRepository.findAll(ORDER_ASC));
    }

    @Override
    @Transactional(readOnly = true)
    public List<StepResponse> listByScenario(UUID scenarioId) {
        findScenarioOrThrow(scenarioId);
        return stepMapper.toResponseList(stepRepository.findByScenarioId(scenarioId, ORDER_ASC));
    }

    @Override
    @Transactional
    public StepResponse update(UUID id, StepRequest request) {
        try {
            Step step = findOrThrow(id);
            Scenario scenario = findScenarioOrThrow(request.scenarioId());

            stepMapper.updateEntityFromRequest(request, step);
            step.setScenario(scenario);
            // status : null = conserver la valeur deja enregistree (jamais
            // un ecrasement silencieux) - voir StepRequest.
            if (request.status() != null) {
                step.setStatus(request.status());
            }

            Step saved = stepRepository.saveAndFlush(step);
            auditLogService.record(AuditAction.UPDATE, AuditModule.STEP, AuditResult.SUCCESS,
                    "Step updated: " + saved.getName());
            return stepMapper.toResponse(saved);
        } catch (RuntimeException e) {
            auditLogService.record(AuditAction.UPDATE, AuditModule.STEP, AuditResult.FAILURE,
                    "Step update failed for id " + id + ": " + e.getClass().getSimpleName());
            throw e;
        }
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        try {
            Step step = findOrThrow(id);
            stepRepository.delete(step);
            auditLogService.record(AuditAction.DELETE, AuditModule.STEP, AuditResult.SUCCESS,
                    "Step deleted: " + step.getName());
        } catch (RuntimeException e) {
            auditLogService.record(AuditAction.DELETE, AuditModule.STEP, AuditResult.FAILURE,
                    "Step deletion failed for id " + id + ": " + e.getClass().getSimpleName());
            throw e;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<StepTestResultResponse> testBatch(StepTestBatchRequest request) {
        List<Step> steps = stepRepository.findAllById(request.stepIds());
        if (steps.size() != request.stepIds().size()) {
            throw new ResourceNotFoundException("Une ou plusieurs etapes selectionnees sont introuvables.");
        }
        long distinctScenarios = steps.stream().map(s -> s.getScenario().getId()).distinct().count();
        if (distinctScenarios > 1) {
            throw new ConflictException("Toutes les etapes selectionnees doivent appartenir au meme scenario.");
        }
        // L'URL de base est celle de l'Application du scenario - exactement
        // comme une vraie Execution (voir ExecutionTransactionHelper),
        // jamais une URL inventee.
        String applicationBaseUrl = steps.get(0).getScenario().getApplication().getUrl();

        Map<UUID, String> stepNameById = steps.stream()
                .collect(Collectors.toMap(Step::getId, Step::getName));
        List<StepExecutionSpec> specs = steps.stream().map(stepMapper::toExecutionSpec).toList();

        List<StepOutcome> outcomes = executionEngine.testSteps(specs, applicationBaseUrl);

        auditLogService.record(AuditAction.TEST, AuditModule.STEP, AuditResult.SUCCESS,
                "Tested " + outcomes.size() + " step(s) in batch");

        Map<UUID, StepExecutionSpec> specById = specs.stream()
                .collect(Collectors.toMap(StepExecutionSpec::stepId, s -> s));
        return outcomes.stream().map(outcome -> {
            StepExecutionSpec spec = specById.get(outcome.stepId());
            boolean assertionConfigured = spec != null && spec.assertionBodyContains() != null
                    && !spec.assertionBodyContains().isBlank();
            Boolean assertionPassed = assertionConfigured
                    ? !(outcome.error() != null && outcome.error().startsWith("Assertion echouee"))
                    : null;
            return new StepTestResultResponse(
                    outcome.stepId().toString(),
                    stepNameById.get(outcome.stepId()),
                    outcome.success(),
                    outcome.httpStatus(),
                    outcome.responseTimeMs(),
                    outcome.error(),
                    assertionPassed);
        }).toList();
    }

    private Step findOrThrow(UUID id) {
        return stepRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Step introuvable : " + id));
    }

    private Scenario findScenarioOrThrow(UUID scenarioId) {
        return scenarioRepository.findById(scenarioId)
                .orElseThrow(() -> new ResourceNotFoundException("Scenario introuvable : " + scenarioId));
    }
}
