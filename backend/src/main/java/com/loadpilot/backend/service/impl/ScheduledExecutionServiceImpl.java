package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.service.audit.AuditEvent;
import com.loadpilot.backend.service.audit.AuditSnapshots;
import com.loadpilot.backend.service.audit.AuditTargetType;
import com.loadpilot.backend.service.audit.AuditTargets;
import com.loadpilot.backend.dto.request.SchedulePreviewRequest;
import com.loadpilot.backend.dto.request.ScheduledExecutionRequest;
import com.loadpilot.backend.dto.response.ScheduleHistoryResponse;
import com.loadpilot.backend.dto.response.SchedulePreviewResponse;
import com.loadpilot.backend.dto.response.ScheduledExecutionResponse;
import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.entity.Scenario;
import com.loadpilot.backend.entity.ScheduledExecution;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.enums.ScenarioStatus;
import com.loadpilot.backend.enums.ScheduleNotifyMode;
import com.loadpilot.backend.enums.ScheduleType;
import com.loadpilot.backend.exception.BadRequestException;
import com.loadpilot.backend.exception.ConflictException;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.repository.AppUserRepository;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.ScenarioRepository;
import com.loadpilot.backend.repository.ScheduledExecutionRepository;
import com.loadpilot.backend.service.AuditLogService;
import com.loadpilot.backend.service.ScheduledExecutionService;
import com.loadpilot.backend.service.execution.ScheduleNextRunCalculator;
import com.loadpilot.backend.service.execution.TriggerClaim;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * P1-B — CRUD + cycle de vie d'une ScheduledExecution. Ne declenche JAMAIS
 * d'Execution elle-meme en dehors de runNow() (voir ScheduledExecutionTrigger
 * et ScheduledExecutionPoller pour le declenchement automatique) - cette
 * classe persiste la configuration, calcule "nextRunAt" et construit la vue
 * (etat, derniere execution, historique) via ScheduleViewService.
 *
 * Les frequences de l'interface sont des expressions cron (5 champs acceptes,
 * normalisees en 6) ; "endAt" ne concerne que les planifications recurrentes.
 */
@Service
public class ScheduledExecutionServiceImpl implements ScheduledExecutionService {

    static final String INACTIVE_NOT_SCHEDULABLE = "Le scénario est inactif, activez-le pour le planifier.";
    static final String RUNNING_NOT_RUNNABLE = "Une exécution de ce scénario est encore en cours : réessayez quand elle sera terminée.";
    static final int HISTORY_MAX = 200;

    private final ScheduledExecutionRepository scheduledExecutionRepository;
    private final ScenarioRepository scenarioRepository;
    private final com.loadpilot.backend.service.ExecutionLimitsService executionLimits;
    private final AppUserRepository appUserRepository;
    private final ExecutionRepository executionRepository;
    private final AuditLogService auditLogService;
    private final ScheduledExecutionTransactionHelper transactionHelper;
    private final ScheduledExecutionTrigger trigger;
    private final ScheduleViewService viewService;
    private final TransactionTemplate readOnlyTx;

    public ScheduledExecutionServiceImpl(ScheduledExecutionRepository scheduledExecutionRepository,
                                         ScenarioRepository scenarioRepository,
                                         AppUserRepository appUserRepository,
                                         ExecutionRepository executionRepository,
                                         AuditLogService auditLogService,
                                         ScheduledExecutionTransactionHelper transactionHelper,
                                         ScheduledExecutionTrigger trigger,
                                         ScheduleViewService viewService,
                                         PlatformTransactionManager transactionManager,
                                         com.loadpilot.backend.service.ExecutionLimitsService executionLimits) {
        this.scheduledExecutionRepository = scheduledExecutionRepository;
        this.scenarioRepository = scenarioRepository;
        this.appUserRepository = appUserRepository;
        this.executionRepository = executionRepository;
        this.auditLogService = auditLogService;
        this.transactionHelper = transactionHelper;
        this.trigger = trigger;
        this.viewService = viewService;
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.executionLimits = executionLimits;
    }

    @Override
    @Transactional
    public ScheduledExecutionResponse create(ScheduledExecutionRequest request, UUID createdByAppUserId) {
        try {
            Scenario scenario = schedulableScenario(request.scenarioId());
            AppUser createdBy = appUserRepository.getReferenceById(createdByAppUserId);

            Instant now = Instant.now();
            String cron = cronFor(request);
            Instant endAt = endAtFor(request);
            Instant nextRunAt = ScheduleNextRunCalculator.computeInitial(
                    request.scheduleType(), request.runAt(), cron, request.timezone(), endAt, now);

            ScheduledExecution schedule = ScheduledExecution.builder()
                    .scenario(scenario)
                    .name(request.name().trim())
                    .scheduleType(request.scheduleType())
                    .cronExpression(cron)
                    .runAt(request.scheduleType() == ScheduleType.ONE_TIME ? request.runAt() : null)
                    .timezone(request.timezone())
                    .endAt(endAt)
                    .notifyMode(request.notifyMode() == null ? ScheduleNotifyMode.ALWAYS : request.notifyMode())
                    .nextRunAt(nextRunAt)
                    .enabled(true)
                    .createdBy(createdBy)
                    .build();
            ScheduledExecution saved = scheduledExecutionRepository.save(schedule);

            auditLogService.record(AuditEvent.of(AuditAction.CREATE, AuditModule.SCHEDULING)
                    .target(AuditTargetType.SCHEDULE, saved.getId(), saved.getName())
                    .diff(Map.of(), AuditSnapshots.of(saved))
                    .description("Planification créée"));
            return view(saved.getId());
        } catch (RuntimeException e) {
            auditLogService.record(AuditEvent.of(AuditAction.CREATE, AuditModule.SCHEDULING)
                    .target(AuditTargetType.SCHEDULE, null, request.name())
                    .description("Création de la planification refusée").failed(e));
            throw e;
        }
    }

    @Override
    @Transactional
    public ScheduledExecutionResponse update(UUID id, ScheduledExecutionRequest request) {
        try {
            ScheduledExecution schedule = scheduledExecutionRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Planification introuvable : " + id));
            Map<String, Object> before = AuditSnapshots.of(schedule);
            Scenario scenario = schedulableScenario(request.scenarioId());

            Instant now = Instant.now();
            String cron = cronFor(request);
            Instant endAt = endAtFor(request);
            Instant nextRunAt = ScheduleNextRunCalculator.computeInitial(
                    request.scheduleType(), request.runAt(), cron, request.timezone(), endAt, now);

            schedule.setScenario(scenario);
            schedule.setName(request.name().trim());
            schedule.setScheduleType(request.scheduleType());
            schedule.setCronExpression(cron);
            schedule.setRunAt(request.scheduleType() == ScheduleType.ONE_TIME ? request.runAt() : null);
            schedule.setTimezone(request.timezone());
            schedule.setEndAt(endAt);
            if (request.notifyMode() != null) schedule.setNotifyMode(request.notifyMode());
            schedule.setNextRunAt(nextRunAt);

            ScheduledExecution saved = scheduledExecutionRepository.save(schedule);
            auditLogService.record(AuditEvent.of(AuditAction.UPDATE, AuditModule.SCHEDULING)
                    .target(AuditTargetType.SCHEDULE, saved.getId(), saved.getName())
                    .diff(before, AuditSnapshots.of(saved))
                    .description("Planification modifiée"));
            return view(saved.getId());
        } catch (RuntimeException e) {
            auditLogService.record(AuditEvent.of(AuditAction.UPDATE, AuditModule.SCHEDULING)
                    .target(AuditTargetType.SCHEDULE, id, request.name())
                    .description("Modification de la planification refusée").failed(e));
            throw e;
        }
    }

    @Override
    public SchedulePreviewResponse preview(SchedulePreviewRequest request) {
        String cron = request.scheduleType() == ScheduleType.RECURRING_CRON
                ? ScheduleNextRunCalculator.normalizeCron(request.cronExpression()) : null;
        Instant endAt = request.scheduleType() == ScheduleType.RECURRING_CRON ? request.endAt() : null;
        List<Instant> next = ScheduleNextRunCalculator.preview(request.scheduleType(), request.runAt(), cron,
                request.timezone(), endAt, Instant.now(), 3);
        return new SchedulePreviewResponse(next, cron);
    }

    @Override
    @Transactional
    public List<ScheduledExecutionResponse> list(UUID scenarioId) {
        List<ScheduledExecution> schedules = scenarioId != null
                ? scheduledExecutionRepository.findByScenarioIdWithScenarioAndApplication(scenarioId)
                : scheduledExecutionRepository.findAllWithScenarioAndApplication();
        Instant now = Instant.now();
        return schedules.stream().map(s -> viewService.toResponse(s, now)).toList();
    }

    @Override
    @Transactional
    public ScheduledExecutionResponse getById(UUID id) {
        return view(id);
    }

    @Override
    @Transactional
    public ScheduleHistoryResponse history(UUID id, int limit) {
        ScheduledExecution schedule = scheduledExecutionRepository.findByIdWithScenarioAndApplication(id)
                .orElseThrow(() -> new ResourceNotFoundException("Planification introuvable : " + id));
        return viewService.history(schedule, Math.max(1, Math.min(HISTORY_MAX, limit)));
    }

    @Override
    @Transactional
    public ScheduledExecutionResponse setEnabled(UUID id, boolean enabled) {
        ScheduledExecution schedule = scheduledExecutionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Planification introuvable : " + id));
        Instant now = Instant.now();
        if (enabled) {
            Scenario scenario = schedule.getScenario();
            if (scenario.getStatus() == ScenarioStatus.INACTIVE) {
                throw new BadRequestException(INACTIVE_NOT_SCHEDULABLE);
            }
            if (scenario.isArchived() || scenario.getApplication().isArchived()) {
                throw new BadRequestException("Le scénario ou son application est archivé : restaurez-le pour reprendre la planification.");
            }
            if (schedule.getScheduleType() == ScheduleType.ONE_TIME && schedule.getNextRunAt() != null
                    && !schedule.getNextRunAt().isAfter(now)) {
                throw new BadRequestException("L'heure de cette planification unique est passée : modifiez-la pour choisir une nouvelle date.");
            }
            if (schedule.getScheduleType() == ScheduleType.RECURRING_CRON) {
                // Reprise : repart de la prochaine occurrence (les lancements prevus pendant la pause ne sont pas rattrapes).
                schedule.setNextRunAt(ScheduleNextRunCalculator.computeAfterTrigger(schedule, now));
            }
        }
        schedule.setEnabled(enabled);
        schedule.setPausedByScenario(false);
        scheduledExecutionRepository.save(schedule);
        auditLogService.record(AuditEvent.of(enabled ? AuditAction.ENABLE : AuditAction.DISABLE, AuditModule.SCHEDULING)
                .target(AuditTargetType.SCHEDULE, id, schedule.getName())
                .change("Statut", enabled ? "En pause" : "Active", enabled ? "Active" : "En pause")
                .description(enabled ? "Planification réactivée" : "Planification mise en pause"));
        return view(id);
    }

    @Override
    public ScheduledExecutionResponse runNow(UUID id) {
        ScheduledExecution schedule = scheduledExecutionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Planification introuvable : " + id));
        // Identifiant lu sur le proxy du scenario : aucun chargement paresseux hors transaction.
        if (executionRepository.countActiveByScenarioId(schedule.getScenario().getId()) > 0) {
            throw new ConflictException(RUNNING_NOT_RUNNABLE);
        }
        TriggerClaim claim = transactionHelper.claimForManualRun(schedule.getId());
        trigger.fire(claim, true);
        return readOnlyTx.execute(t -> view(id));
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        ScheduledExecution schedule = scheduledExecutionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Planification introuvable : " + id));
        scheduledExecutionRepository.delete(schedule);
        auditLogService.record(AuditEvent.of(AuditAction.DELETE, AuditModule.SCHEDULING)
                .target(AuditTargetType.SCHEDULE, id, schedule.getName())
                .description("Planification supprimée (les exécutions déjà lancées sont conservées)"));
    }

    private Scenario schedulableScenario(UUID scenarioId) {
        Scenario scenario = scenarioRepository.findById(scenarioId)
                .orElseThrow(() -> new ResourceNotFoundException("Scenario introuvable : " + scenarioId));
        if (scenario.getStatus() == ScenarioStatus.DRAFT) {
            throw new BadRequestException(
                    "Ce scénario est un brouillon : terminez-le dans l'assistant (« Reprendre ») avant de le planifier.");
        }
        if (scenario.getStatus() == ScenarioStatus.INACTIVE) {
            throw new BadRequestException(INACTIVE_NOT_SCHEDULABLE);
        }
        if (scenario.isArchived() || scenario.getApplication().isArchived()) {
            throw new BadRequestException("Ce scénario (ou son application) est archivé : restaurez-le avant de le planifier.");
        }
        java.util.List<String> loadProblems = com.loadpilot.backend.service.validation.LoadRules.validate(scenario.getVirtualUsers(),
                scenario.getThinkTimeMs(), scenario.getRampUpSeconds(), scenario.getDurationSeconds(), scenario.getIterations(),
                scenario.getTargetRps(), executionLimits.loadLimits());
        if (!loadProblems.isEmpty()) {
            throw new BadRequestException(String.join(" ", loadProblems) + " Modifiez la charge du scénario avant de le planifier.");
        }
        return scenario;
    }

    private static String cronFor(ScheduledExecutionRequest request) {
        return request.scheduleType() == ScheduleType.RECURRING_CRON
                ? ScheduleNextRunCalculator.normalizeCron(request.cronExpression()) : null;
    }

    private static Instant endAtFor(ScheduledExecutionRequest request) {
        return request.scheduleType() == ScheduleType.RECURRING_CRON ? request.endAt() : null;
    }

    private ScheduledExecutionResponse view(UUID id) {
        ScheduledExecution schedule = scheduledExecutionRepository.findByIdWithScenarioAndApplication(id)
                .orElseThrow(() -> new ResourceNotFoundException("Planification introuvable : " + id));
        return viewService.toResponse(schedule, Instant.now());
    }
}
