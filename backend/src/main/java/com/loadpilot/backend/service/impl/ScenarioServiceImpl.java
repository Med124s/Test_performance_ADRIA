package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.service.audit.AuditEvent;
import com.loadpilot.backend.service.audit.AuditSnapshots;
import com.loadpilot.backend.service.audit.AuditTargetType;
import com.loadpilot.backend.dto.request.ScenarioRequest;
import com.loadpilot.backend.dto.response.ScenarioResponse;
import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.entity.Scenario;
import com.loadpilot.backend.entity.Step;
import com.loadpilot.backend.exception.BadRequestException;
import com.loadpilot.backend.service.execution.CsvDataSource;
import com.loadpilot.backend.service.validation.ScenarioVariables;
import com.loadpilot.backend.service.validation.StepRules;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.enums.LoadMode;
import com.loadpilot.backend.enums.ScenarioStatus;
import com.loadpilot.backend.exception.ConflictException;
import com.loadpilot.backend.exception.LoadConfigurationException;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.mapper.ScenarioMapper;
import com.loadpilot.backend.repository.ApplicationRepository;
import com.loadpilot.backend.repository.ScenarioRepository;
import com.loadpilot.backend.repository.StepRepository;
import com.loadpilot.backend.security.CurrentUser;
import com.loadpilot.backend.service.AppUserSyncService;
import com.loadpilot.backend.service.AuditLogService;
import com.loadpilot.backend.service.ScenarioService;
import com.loadpilot.backend.dto.response.PagedResponse;
import com.loadpilot.backend.dto.request.ScenarioFullRequest;
import com.loadpilot.backend.dto.response.ScenarioFullResponse;
import com.loadpilot.backend.specification.ScenarioSpecifications;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Gestion des scenarios : creation, modification, publication des brouillons, archivage et suppression. */
@Service
@RequiredArgsConstructor
public class ScenarioServiceImpl implements ScenarioService {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt");
    private static final Sort STEP_ORDER = Sort.by(Sort.Direction.ASC, "order").and(Sort.by(Sort.Direction.ASC, "id"));
    private static final Set<String> SORTABLE = Set.of("createdAt", "updatedAt", "name");

    private final ScenarioRepository scenarioRepository;
    private final ApplicationRepository applicationRepository;
    private final StepRepository stepRepository;
    private final ScenarioMapper scenarioMapper;
    private final AppUserSyncService appUserSyncService;
    private final AuditLogService auditLogService;
    private final DeletionServiceImpl deletionService;
    private final ScenarioStatusService scenarioStatusService;
    private final com.loadpilot.backend.mapper.StepMapper stepMapper;
    private final com.loadpilot.backend.service.AppSettingsService settings;
    private final com.loadpilot.backend.service.ExecutionLimitsService executionLimits;
    private final com.loadpilot.backend.repository.ScheduledExecutionRepository scheduledExecutionRepository;

    @Override
    @Transactional
    public ScenarioResponse create(ScenarioRequest request, CurrentUser currentUser) {
        try {
            Application application = findApplicationOrThrow(request.applicationId());
            checkCsv(request.csvData());
            AppUser createdBy = appUserSyncService.sync(currentUser);

            Scenario scenario = scenarioMapper.toEntity(request);
            scenario.setApplication(application);
            scenario.setCreatedBy(createdBy);
            // ACTIVE par defaut ; DRAFT pour un brouillon de l'assistant.
            boolean draft = Boolean.TRUE.equals(request.draft());
            scenario.setStatus(draft ? ScenarioStatus.DRAFT : ScenarioStatus.ACTIVE);
            if (draft) {
                scenario.setWizardStep(request.wizardStep());
                scenario.setDraftState(request.draftState());
            }
            applyLoadMode(scenario, request);
            applyLoadTestDefaults(scenario, request);
            // Seuils non fournis : valeurs par defaut de Parametres > Systeme.
            if (request.maxErrorRatePercent() == null) {
                scenario.setMaxErrorRatePercent(settings.defaultMaxErrorRate());
            }
            if (request.maxP95Ms() == null) {
                long p95 = settings.defaultMaxP95Ms();
                scenario.setMaxP95Ms(p95 > 0 ? p95 : null);
            }
            applyVariables(scenario, request);
            applyCreationDefaults(scenario, request);

            Scenario saved = scenarioRepository.saveAndFlush(scenario);
            auditLogService.record(AuditEvent.of(AuditAction.CREATE, AuditModule.SCENARIO)
                    .target(AuditTargetType.SCENARIO, saved.getId(), saved.getName())
                    .diff(Map.of(), AuditSnapshots.of(saved))
                    .description(draft ? "Brouillon de scénario créé" : "Scénario créé"));
            return withLoadWarnings(scenarioMapper.toResponse(saved));
        } catch (RuntimeException e) {
            auditLogService.record(AuditEvent.of(AuditAction.CREATE, AuditModule.SCENARIO)
                    .target(AuditTargetType.SCENARIO, null, request.name())
                    .description("Création refusée").failed(e));
            throw e;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ScenarioResponse getById(UUID id) {
        return withPausedSchedules(List.of(scenarioMapper.toResponse(findOrThrow(id)))).get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ScenarioResponse> list(boolean archived) {
        return withPausedSchedules(scenarioMapper.toResponseList(archived
                ? scenarioRepository.findArchivedView(NEWEST_FIRST)
                : scenarioRepository.findVisible(NEWEST_FIRST)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ScenarioResponse> listByApplication(UUID applicationId, boolean archived) {
        findApplicationOrThrow(applicationId);
        return withPausedSchedules(scenarioMapper.toResponseList(archived
                ? scenarioRepository.findArchivedViewByApplicationId(applicationId, NEWEST_FIRST)
                : scenarioRepository.findVisibleByApplicationId(applicationId, NEWEST_FIRST)));
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<ScenarioResponse> page(int page, int size, String sort, String search, String status,
            UUID applicationId, boolean archived) {
        int safeSize = Math.max(5, Math.min(100, size));
        int safePage = Math.max(0, page);
        Page<Scenario> result = scenarioRepository.findAll(
                ScenarioSpecifications.matching(search, parseStatus(status), applicationId, archived),
                PageRequest.of(safePage, safeSize, parseSort(sort)));
        return new PagedResponse<>(withPausedSchedules(scenarioMapper.toResponseList(result.getContent())),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    private static Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) return Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.ASC, "id"));
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        if (!SORTABLE.contains(field)) {
            throw new LoadConfigurationException("Tri non autorisé : " + field + " (champs possibles : createdAt, updatedAt, name).");
        }
        Sort.Direction direction = parts.length > 1 && parts[1].trim().equalsIgnoreCase("desc") ? Sort.Direction.DESC : Sort.Direction.ASC;
        // Tri secondaire stable d'une page a l'autre.
        return Sort.by(direction, field).and(Sort.by(Sort.Direction.ASC, "id"));
    }

    private static ScenarioStatus parseStatus(String status) {
        if (status == null || status.isBlank()) return null;
        try {
            return ScenarioStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new LoadConfigurationException("Statut inconnu : " + status + " (ACTIVE, INACTIVE ou DRAFT).");
        }
    }

    /** Ajoute le nombre de planifications suspendues (une seule requete pour toute la liste). */
    private List<ScenarioResponse> withPausedSchedules(List<ScenarioResponse> responses) {
        Map<UUID, Long> counts = scenarioStatusService.pausedSchedulesCount(
                responses.stream().map(r -> UUID.fromString(r.id())).toList());
        com.loadpilot.backend.service.validation.LoadLimits limits = executionLimits.loadLimits();
        return responses.stream()
                .map(r -> withLoadWarnings(r.withPausedSchedules(counts.getOrDefault(UUID.fromString(r.id()), 0L)), limits))
                .toList();
    }

    /** Ajoute les avertissements de charge (non bloquants). */
    private ScenarioResponse withLoadWarnings(ScenarioResponse response) {
        return withLoadWarnings(response, executionLimits.loadLimits());
    }

    private static ScenarioResponse withLoadWarnings(ScenarioResponse response, com.loadpilot.backend.service.validation.LoadLimits limits) {
        // Mode stress : aucun reglage de charge saisi, donc aucun avertissement.
        if (response.loadMode() == LoadMode.STRESS) return response.withLoadWarnings(List.of());
        return response.withLoadWarnings(com.loadpilot.backend.service.validation.LoadRules.warnings(
                response.virtualUsers(), response.targetRps(), limits));
    }

    @Override
    @Transactional
    public ScenarioResponse update(UUID id, ScenarioRequest request) {
        try {
            Scenario scenario = findOrThrow(id);
            Map<String, Object> before = AuditSnapshots.of(scenario);
            Application application = findApplicationOrThrow(request.applicationId());
            checkCsv(request.csvData());
            if (deletionService.runningForScenario(id) > 0) {
                throw new ConflictException(RUNNING_EDIT_MESSAGE);
            }

            scenarioMapper.updateEntityFromRequest(request, scenario);
            scenario.setApplication(application);
            applyLoadMode(scenario, request);
            applyLoadTestDefaults(scenario, request);
            applyVariables(scenario, request);
            // Etat de l'assistant : brouillons uniquement ; null = inchange.
            if (scenario.getStatus() == ScenarioStatus.DRAFT) {
                if (request.wizardStep() != null) scenario.setWizardStep(request.wizardStep());
                if (request.draftState() != null) scenario.setDraftState(request.draftState());
            }

            Scenario saved = scenarioRepository.saveAndFlush(scenario);
            AuditEvent audit = AuditEvent.of(AuditAction.UPDATE, AuditModule.SCENARIO)
                    .target(AuditTargetType.SCENARIO, saved.getId(), saved.getName())
                    .diff(before, AuditSnapshots.of(saved));
            auditLogService.record(audit.description(audit.changes().isEmpty() ? "Scénario enregistré sans changement de ses réglages" : "Scénario modifié"));
            return withLoadWarnings(scenarioMapper.toResponse(saved));
        } catch (RuntimeException e) {
            auditLogService.record(AuditEvent.of(AuditAction.UPDATE, AuditModule.SCENARIO)
                    .target(AuditTargetType.SCENARIO, id, scenarioName(id))
                    .description("Modification refusée").failed(e));
            throw e;
        }
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        // Suppression definitive (Super administrateur), refusee si une execution est en cours.
        deletionService.deleteScenarioPermanently(id);
    }

    /** Publie un brouillon : il devient ACTIVE s'il est complet (au moins une etape, etapes valides, CSV valide, charge valide). */
    @Override
    @Transactional
    public ScenarioResponse publish(UUID id) {
        Scenario scenario = findOrThrow(id);
        if (scenario.getStatus() != ScenarioStatus.DRAFT) {
            return withLoadWarnings(scenarioMapper.toResponse(scenario));
        }
        if (scenario.isArchived() || scenario.getApplication().isArchived()) {
            throw new ConflictException("Ce brouillon ou son application est archivé : restaurez-le avant de l'enregistrer.");
        }
        List<Step> steps = stepRepository.findByScenarioId(id, STEP_ORDER);
        if (steps.isEmpty()) {
            throw new BadRequestException("Ajoutez au moins une étape avant d'enregistrer le scénario.");
        }
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            String problem = StepRules.validate(step.getUrl(), step.getHeaders(), step.getBody(),
                    step.getCaptureVariableName(), step.getCaptureJsonPath(), step.getCaptureSource());
            if (problem == null) problem = StepRules.validateParams(step.getPathParams(), step.getQueryParams());
            if (problem == null) {
                problem = com.loadpilot.backend.service.execution.StepChecks.configurationProblem(step.getAssertionJsonPath(),
                        step.getAssertionJsonOperator(), step.getAssertionJsonValue());
            }
            if (problem != null) {
                throw new BadRequestException("Étape " + (i + 1) + " « " + step.getName() + " » : " + problem);
            }
        }
        // Duree et iterations optionnelles : aucune = une iteration par utilisateur, les deux = arret a la premiere atteinte.
        checkCsv(scenario.getCsvData());
        // Memes regles de charge qu'a l'enregistrement d'un scenario actif.
        List<String> loadProblems = loadProblems(scenario);
        if (!loadProblems.isEmpty()) {
            throw new BadRequestException(String.join(" ", loadProblems));
        }
        scenario.setStatus(ScenarioStatus.ACTIVE);
        scenario.setWizardStep(null);
        scenario.setDraftState(null);
        Scenario saved = scenarioRepository.saveAndFlush(scenario);
        auditLogService.record(AuditEvent.of(AuditAction.UPDATE, AuditModule.SCENARIO)
                .target(AuditTargetType.SCENARIO, saved.getId(), saved.getName())
                .change("Statut", "Brouillon", "Actif")
                .description("Brouillon publié : le scénario peut être lancé"));
        return withLoadWarnings(scenarioMapper.toResponse(saved));
    }

    @Override
    @Transactional
    public void deleteDraft(UUID id) {
        Scenario scenario = findOrThrow(id);
        if (scenario.getStatus() != ScenarioStatus.DRAFT) {
            throw new ConflictException("Ce scénario n'est pas un brouillon : utilisez la suppression habituelle.");
        }
        deletionService.deleteScenarioPermanently(id);
    }

    public static final String RUNNING_EDIT_MESSAGE =
            "Une exécution de ce scénario est en cours : attendez sa fin ou annulez-la avant de le modifier.";

    /** Modification complete en une transaction : informations du scenario et etapes creees, modifiees, supprimees ou reordonnees. */
    @Override
    @Transactional
    public ScenarioFullResponse updateFull(UUID id, ScenarioFullRequest request) {
        Scenario scenario = findOrThrow(id);
        if (scenario.isArchived() || scenario.getApplication().isArchived()) {
            throw new ConflictException("Ce scénario ou son application est archivé : restaurez-le avant de le modifier.");
        }
        if (deletionService.runningForScenario(id) > 0) {
            throw new ConflictException(RUNNING_EDIT_MESSAGE);
        }
        List<ScenarioFullRequest.StepPayload> payloads = request.steps();
        if (payloads.isEmpty() && scenario.getStatus() != ScenarioStatus.DRAFT) {
            throw new BadRequestException("Un scénario doit contenir au moins une étape.");
        }
        for (int i = 0; i < payloads.size(); i++) {
            String problem = StepRules.validate(payloads.get(i).step());
            if (problem != null) {
                throw new BadRequestException("Étape " + (i + 1) + " « " + payloads.get(i).step().name() + " » : " + problem);
            }
        }
        update(id, request.scenario());

        Map<UUID, Step> existing = new java.util.HashMap<>();
        stepRepository.findByScenarioId(id, STEP_ORDER).forEach(s -> existing.put(s.getId(), s));
        Set<UUID> kept = new java.util.HashSet<>();
        int created = 0;
        int modified = 0;
        List<Step> ordered = new java.util.ArrayList<>();
        for (int i = 0; i < payloads.size(); i++) {
            ScenarioFullRequest.StepPayload payload = payloads.get(i);
            Step step = payload.id() != null ? existing.get(payload.id()) : null;
            if (payload.id() != null && step == null) {
                throw new BadRequestException("Étape " + (i + 1) + " : elle n'appartient pas à ce scénario (rechargez la page).");
            }
            if (step == null) {
                step = stepMapper.toEntity(payload.step());
                step.setScenario(scenario);
                step.setStatus(com.loadpilot.backend.enums.StepStatus.ACTIVE);
                created++;
            } else {
                stepMapper.updateEntityFromRequest(payload.step(), step);
                step.setScenario(scenario);
                kept.add(step.getId());
                modified++;
            }
            step.setOrder(i + 1);
            ordered.add(step);
        }
        List<Step> removed = existing.values().stream().filter(s -> !kept.contains(s.getId())).toList();
        // Les resultats passes gardent leur copie de l'etape.
        stepRepository.deleteAll(removed);
        stepRepository.flush();
        List<Step> saved = stepRepository.saveAllAndFlush(ordered);
        auditLogService.record(AuditEvent.of(AuditAction.UPDATE, AuditModule.SCENARIO)
                .target(AuditTargetType.SCENARIO, scenario.getId(), scenario.getName())
                .change("Nombre d’étapes", existing.size(), saved.size())
                .description("Étapes enregistrées : " + created + " ajoutée(s), " + modified + " conservée(s), " + removed.size() + " supprimée(s)"));
        return new ScenarioFullResponse(getById(id), stepMapper.toResponseList(saved));
    }

    /** Nouveau scenario : reglages non fournis (frein, 429, detail des requetes) pris dans Parametres > Systeme et ecrits dans le scenario. */
    private void applyCreationDefaults(Scenario scenario, ScenarioRequest request) {
        if (request.errorBrakeRatePercent() == null && settings.defaultErrorBrakeEnabled()) {
            scenario.setErrorBrakeRatePercent(settings.defaultErrorBrakeRate());
        }
        if (request.errorBrakeWindowSeconds() == null && scenario.getErrorBrakeRatePercent() != null) {
            scenario.setErrorBrakeWindowSeconds(settings.defaultErrorBrakeWindow());
        }
        if (request.throttleOn429() == null) scenario.setThrottleOn429(settings.defaultThrottleOn429());
        if (request.throttleDefaultDelaySeconds() == null) scenario.setThrottleDefaultDelaySeconds(settings.defaultThrottleDelay());
        if (request.throttleMaxRetries() == null) scenario.setThrottleMaxRetries(settings.defaultThrottleRetries());
        if (request.requestDetailMode() == null) scenario.setRequestDetailMode(settings.defaultRequestDetailMode());
    }

    /** Variables du scenario et distribution du CSV : null = inchange ("{}" pour tout effacer). */
    private void applyVariables(Scenario scenario, ScenarioRequest request) {
        if (request.variables() != null) {
            scenario.setVariables(ScenarioVariables.normalize(request.variables()));
        }
        if (request.csvDistribution() != null) {
            scenario.setCsvDistribution(request.csvDistribution());
        } else if (scenario.getCsvDistribution() == null) {
            scenario.setCsvDistribution(com.loadpilot.backend.enums.CsvDistribution.PER_VIRTUAL_USER);
        }
        // Detail des requetes : null = inchange (defaut a la creation : echecs + echantillon).
        if (request.requestDetailMode() != null) {
            scenario.setRequestDetailMode(request.requestDetailMode());
        } else if (scenario.getRequestDetailMode() == null) {
            scenario.setRequestDetailMode(com.loadpilot.backend.enums.RequestDetailMode.FAILURES_AND_SAMPLE);
        }
        // Seuils de reussite : null = inchange ; P95 maximal 0 = aucun seuil de P95.
        if (request.maxErrorRatePercent() != null) {
            scenario.setMaxErrorRatePercent(request.maxErrorRatePercent());
        } else if (scenario.getMaxErrorRatePercent() == null) {
            scenario.setMaxErrorRatePercent(1.0);
        }
        if (request.maxP95Ms() != null) {
            scenario.setMaxP95Ms(request.maxP95Ms() > 0 ? request.maxP95Ms() : null);
        }
        // Frein automatique : null = inchange ; 0 = desactive.
        if (request.errorBrakeRatePercent() != null) {
            scenario.setErrorBrakeRatePercent(request.errorBrakeRatePercent() > 0 ? request.errorBrakeRatePercent() : null);
        }
        if (request.errorBrakeWindowSeconds() != null) {
            scenario.setErrorBrakeWindowSeconds(request.errorBrakeWindowSeconds());
        }
        // Ralentissement sur 429 : null = inchange.
        if (request.throttleOn429() != null) {
            scenario.setThrottleOn429(request.throttleOn429());
        }
        if (request.throttleDefaultDelaySeconds() != null) {
            scenario.setThrottleDefaultDelaySeconds(request.throttleDefaultDelaySeconds());
        }
        if (request.throttleMaxRetries() != null) {
            scenario.setThrottleMaxRetries(request.throttleMaxRetries());
        }
        // Variation du think time : null = inchange ; 0 = think time fixe (enregistre comme absent).
        if (request.thinkTimeVariationPercent() != null) {
            scenario.setThinkTimeVariationPercent(request.thinkTimeVariationPercent() > 0 ? request.thinkTimeVariationPercent() : null);
        }
    }

    private void checkCsv(String csvData) {
        String problem = CsvDataSource.validate(csvData);
        if (problem != null) throw new BadRequestException("CSV invalide : " + problem);
    }

    /** Valeurs par defaut des parametres de charge non fournis. */
    private void applyLoadTestDefaults(Scenario scenario, ScenarioRequest request) {
        scenario.setVirtualUsers(request.virtualUsers() != null ? request.virtualUsers() : 1);
        scenario.setRampUpSeconds(request.rampUpSeconds() != null ? request.rampUpSeconds() : 0);
        scenario.setDurationSeconds(request.durationSeconds());
        scenario.setIterations(request.iterations());
        scenario.setThinkTimeMs(request.thinkTimeMs() != null ? request.thinkTimeMs() : 0);
        // Un brouillon peut garder un ramp-up plus long que la duree : verifie a la publication.
        List<String> problems = new java.util.ArrayList<>(loadProblems(scenario));
        if (scenario.getStatus() == ScenarioStatus.DRAFT) problems.remove(com.loadpilot.backend.service.validation.LoadRules.RAMP_UP_TOO_LONG);
        if (!problems.isEmpty()) throw new LoadConfigurationException(String.join(" ", problems));
    }

    /** Regles de charge non respectees, avec les limites en vigueur ; aucune en mode stress (reglages ignores). */
    private List<String> loadProblems(Scenario s) {
        if (s.getLoadMode() == LoadMode.STRESS) return List.of();
        return com.loadpilot.backend.service.validation.LoadRules.validate(s.getVirtualUsers(), s.getThinkTimeMs(), s.getRampUpSeconds(),
                s.getDurationSeconds(), s.getIterations(), s.getTargetRps(), executionLimits.loadLimits());
    }

    /**
     * Mode de charge et vitesse de montee du mode stress : null = inchanges (MANUAL et vitesse par defaut a la creation) ;
     * STRESS refuse tant que le scenario a des planifications.
     */
    private void applyLoadMode(Scenario scenario, ScenarioRequest request) {
        if (request.loadMode() != null) {
            scenario.setLoadMode(request.loadMode());
        } else if (scenario.getLoadMode() == null) {
            scenario.setLoadMode(LoadMode.MANUAL);
        }
        if (request.stressRampStep() != null) scenario.setStressRampStep(request.stressRampStep());
        if (scenario.getLoadMode() == LoadMode.STRESS && scenario.getId() != null) {
            long schedules = scheduledExecutionRepository.countByScenarioId(scenario.getId());
            if (schedules > 0) throw new ConflictException(stressScheduledMessage(schedules));
        }
    }

    static String stressScheduledMessage(long schedules) {
        return "Ce scénario a " + schedules + " planification(s) : supprimez-les avant de passer en mode stress "
                + "(un test de stress se lance à la main, il ne se planifie pas).";
    }

    private Scenario findOrThrow(UUID id) {
        return scenarioRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Scenario introuvable : " + id));
    }

    private Application findApplicationOrThrow(UUID applicationId) {
        return applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Application introuvable : " + applicationId));
    }

    /** Nom du scenario pour le journal ; ne leve jamais d'exception. */
    private String scenarioName(UUID id) {
        try {
            return scenarioRepository.findById(id).map(Scenario::getName).orElse("Scénario introuvable");
        } catch (RuntimeException e) {
            return "Scénario introuvable";
        }
    }
}
