package com.loadpilot.backend.service.impl;

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

/** Audit (Phase 12) : voir le commentaire equivalent sur ApplicationServiceImpl. */
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

    /** P0-B — limite PAR EXECUTION configurable par environnement (voir
     * application.yml, LOADPILOT_MAX_VUS_PER_EXECUTION) - distincte du
     * plafond absolu @Max sur ScenarioRequest (garde-fou de bon sens quel
     * que soit le deploiement, jamais retire). Verifiee ici (a la
     * configuration du Scenario) plutot qu'au lancement de l'Execution :
     * retour immediat et explicite a l'utilisateur, avant meme de tenter un
     * test de charge. */
    @Value("${app.execution.max-virtual-users-per-execution}")
    private int maxVirtualUsersPerExecution;

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
            // ACTIVE par defaut ; DRAFT pour l'assistant (brouillon ou test avant la fin),
            // publie ensuite via publish() apres validation complete.
            boolean draft = Boolean.TRUE.equals(request.draft());
            scenario.setStatus(draft ? ScenarioStatus.DRAFT : ScenarioStatus.ACTIVE);
            if (draft) {
                scenario.setWizardStep(request.wizardStep());
                scenario.setDraftState(request.draftState());
            }
            applyLoadTestDefaults(scenario, request);
            applyVariables(scenario, request);

            Scenario saved = scenarioRepository.saveAndFlush(scenario);
            auditLogService.record(AuditAction.CREATE, AuditModule.SCENARIO, AuditResult.SUCCESS,
                    (draft ? "Scenario draft created: " : "Scenario created: ") + saved.getName());
            return scenarioMapper.toResponse(saved);
        } catch (RuntimeException e) {
            auditLogService.record(AuditAction.CREATE, AuditModule.SCENARIO, AuditResult.FAILURE,
                    "Scenario creation failed: " + e.getClass().getSimpleName());
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
        // Tri secondaire stable : l'ordre des lignes ne change pas d'une page a l'autre.
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
        return responses.stream()
                .map(r -> r.withPausedSchedules(counts.getOrDefault(UUID.fromString(r.id()), 0L)))
                .toList();
    }

    @Override
    @Transactional
    public ScenarioResponse update(UUID id, ScenarioRequest request) {
        try {
            Scenario scenario = findOrThrow(id);
            Application application = findApplicationOrThrow(request.applicationId());
            checkCsv(request.csvData());
            if (deletionService.runningForScenario(id) > 0) {
                throw new ConflictException(RUNNING_EDIT_MESSAGE);
            }

            scenarioMapper.updateEntityFromRequest(request, scenario);
            scenario.setApplication(application);
            applyLoadTestDefaults(scenario, request);
            applyVariables(scenario, request);
            // Etat de l'assistant : brouillons uniquement, null = inchange (le test d'un
            // brouillon repris ne remplace jamais l'etat enregistre explicitement).
            if (scenario.getStatus() == ScenarioStatus.DRAFT) {
                if (request.wizardStep() != null) scenario.setWizardStep(request.wizardStep());
                if (request.draftState() != null) scenario.setDraftState(request.draftState());
            }

            Scenario saved = scenarioRepository.saveAndFlush(scenario);
            auditLogService.record(AuditAction.UPDATE, AuditModule.SCENARIO, AuditResult.SUCCESS,
                    "Scenario updated: " + saved.getName());
            return scenarioMapper.toResponse(saved);
        } catch (RuntimeException e) {
            auditLogService.record(AuditAction.UPDATE, AuditModule.SCENARIO, AuditResult.FAILURE,
                    "Scenario update failed for id " + id + ": " + e.getClass().getSimpleName());
            throw e;
        }
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        // Suppression DEFINITIVE explicitement demandee (SUPER_ADMIN) : cascade
        // complete (etapes, executions, resultats, planifications) dans une
        // seule transaction, refusee si une execution est en cours - voir
        // DeletionServiceImpl (audit inclus).
        deletionService.deleteScenarioPermanently(id);
    }

    /**
     * Fin de l'assistant : un brouillon devient ACTIVE (executable, planifiable) uniquement
     * s'il est complet - memes regles que le bouton « Suivant » du frontend : au moins une
     * etape, chaque etape valide (StepRules), CSV valide, duree ou nombre de passages.
     * Idempotent pour un scenario deja ACTIVE.
     */
    @Override
    @Transactional
    public ScenarioResponse publish(UUID id) {
        Scenario scenario = findOrThrow(id);
        if (scenario.getStatus() != ScenarioStatus.DRAFT) {
            return scenarioMapper.toResponse(scenario);
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
                    step.getCaptureVariableName(), step.getCaptureJsonPath());
            if (problem == null) problem = StepRules.validateParams(step.getPathParams(), step.getQueryParams());
            if (problem != null) {
                throw new BadRequestException("Étape " + (i + 1) + " « " + step.getName() + " » : " + problem);
            }
        }
        // Duree et iterations optionnelles (memes regles que le moteur) : aucune des deux = une
        // seule iteration par utilisateur, les deux = arret a la premiere limite atteinte.
        checkCsv(scenario.getCsvData());
        scenario.setStatus(ScenarioStatus.ACTIVE);
        scenario.setWizardStep(null);
        scenario.setDraftState(null);
        Scenario saved = scenarioRepository.saveAndFlush(scenario);
        auditLogService.record(AuditAction.UPDATE, AuditModule.SCENARIO, AuditResult.SUCCESS,
                "Scenario draft published: " + saved.getName());
        return scenarioMapper.toResponse(saved);
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

    /**
     * Modification complete en UNE transaction : informations du scenario, puis etapes creees /
     * modifiees / supprimees / reordonnees selon la liste recue (position = ordre 1..n). Tout ou
     * rien : une etape invalide annule l'ensemble. Refusee pendant une execution du scenario.
     */
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
        // Les resultats d'execution passes gardent leur copie de l'etape (FK ON DELETE SET NULL).
        stepRepository.deleteAll(removed);
        stepRepository.flush();
        List<Step> saved = stepRepository.saveAllAndFlush(ordered);
        auditLogService.record(AuditAction.UPDATE, AuditModule.SCENARIO, AuditResult.SUCCESS,
                "Scenario fully updated: " + scenario.getName() + " (" + created + " step(s) created, " + modified
                        + " updated, " + removed.size() + " deleted)");
        return new ScenarioFullResponse(getById(id), stepMapper.toResponseList(saved));
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
    }

    private void checkCsv(String csvData) {
        String problem = CsvDataSource.validate(csvData);
        if (problem != null) throw new BadRequestException("CSV invalide : " + problem);
    }

    /**
     * Applique les VRAIES valeurs par defaut (P0-A) des parametres de
     * charge optionnels de ScenarioRequest - jamais MapStruct (voir
     * ScenarioMapper, qui les ignore explicitement) pour ne jamais risquer
     * de persister un null dans une colonne NOT NULL. durationSeconds et
     * iterations restent nullables tels quels (absence de limite = valide).
     */
    private void applyLoadTestDefaults(Scenario scenario, ScenarioRequest request) {
        if (request.virtualUsers() != null && request.virtualUsers() > maxVirtualUsersPerExecution) {
            throw new LoadConfigurationException(
                    "Le nombre d'utilisateurs virtuels (" + request.virtualUsers()
                            + ") depasse la limite configuree pour ce deploiement (" + maxVirtualUsersPerExecution + ").");
        }
        scenario.setVirtualUsers(request.virtualUsers() != null ? request.virtualUsers() : 1);
        scenario.setRampUpSeconds(request.rampUpSeconds() != null ? request.rampUpSeconds() : 0);
        scenario.setDurationSeconds(request.durationSeconds());
        scenario.setIterations(request.iterations());
        scenario.setThinkTimeMs(request.thinkTimeMs() != null ? request.thinkTimeMs() : 0);
    }

    private Scenario findOrThrow(UUID id) {
        return scenarioRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Scenario introuvable : " + id));
    }

    private Application findApplicationOrThrow(UUID applicationId) {
        return applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Application introuvable : " + applicationId));
    }
}
