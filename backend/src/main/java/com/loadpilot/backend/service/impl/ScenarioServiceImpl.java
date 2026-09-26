package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.dto.request.ScenarioRequest;
import com.loadpilot.backend.dto.response.ScenarioResponse;
import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.entity.Scenario;
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
import java.util.List;
import java.util.UUID;
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

    private final ScenarioRepository scenarioRepository;
    private final ApplicationRepository applicationRepository;
    private final StepRepository stepRepository;
    private final ScenarioMapper scenarioMapper;
    private final AppUserSyncService appUserSyncService;
    private final AuditLogService auditLogService;

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
            AppUser createdBy = appUserSyncService.sync(currentUser);

            Scenario scenario = scenarioMapper.toEntity(request);
            scenario.setApplication(application);
            scenario.setCreatedBy(createdBy);
            // Choix declaratif de l'utilisateur, vrai des la creation - voir
            // ScenarioStatus (different d'un resultat d'execution invente).
            scenario.setStatus(ScenarioStatus.ACTIVE);
            applyLoadTestDefaults(scenario, request);

            Scenario saved = scenarioRepository.saveAndFlush(scenario);
            auditLogService.record(AuditAction.CREATE, AuditModule.SCENARIO, AuditResult.SUCCESS,
                    "Scenario created: " + saved.getName());
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
        return scenarioMapper.toResponse(findOrThrow(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ScenarioResponse> list() {
        return scenarioMapper.toResponseList(scenarioRepository.findAll(NEWEST_FIRST));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ScenarioResponse> listByApplication(UUID applicationId) {
        findApplicationOrThrow(applicationId);
        return scenarioMapper.toResponseList(scenarioRepository.findByApplicationId(applicationId, NEWEST_FIRST));
    }

    @Override
    @Transactional
    public ScenarioResponse update(UUID id, ScenarioRequest request) {
        try {
            Scenario scenario = findOrThrow(id);
            Application application = findApplicationOrThrow(request.applicationId());

            scenarioMapper.updateEntityFromRequest(request, scenario);
            scenario.setApplication(application);
            applyLoadTestDefaults(scenario, request);

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
        try {
            Scenario scenario = findOrThrow(id);
            // Modification necessaire pour la relation Step (Phase 8) : sans ce
            // garde-fou, supprimer un Scenario ayant des Steps violerait la FK
            // step.scenario_id (NOT NULL, sans cascade) et remonterait une
            // DataIntegrityViolationException brute en 500 au lieu d'un 409
            // propre - meme philosophie que Application -> Scenario (Phase 7).
            if (stepRepository.existsByScenarioId(id)) {
                throw new ConflictException(
                        "Impossible de supprimer le scenario : des etapes y sont encore rattachees.");
            }
            scenarioRepository.delete(scenario);
            auditLogService.record(AuditAction.DELETE, AuditModule.SCENARIO, AuditResult.SUCCESS,
                    "Scenario deleted: " + scenario.getName());
        } catch (RuntimeException e) {
            auditLogService.record(AuditAction.DELETE, AuditModule.SCENARIO, AuditResult.FAILURE,
                    "Scenario deletion failed for id " + id + ": " + e.getClass().getSimpleName());
            throw e;
        }
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
