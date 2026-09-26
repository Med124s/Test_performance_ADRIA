package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.dto.request.ScheduledExecutionRequest;
import com.loadpilot.backend.dto.response.ScheduledExecutionResponse;
import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.entity.ScheduledExecution;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.mapper.ScheduledExecutionMapper;
import com.loadpilot.backend.repository.AppUserRepository;
import com.loadpilot.backend.repository.ScenarioRepository;
import com.loadpilot.backend.repository.ScheduledExecutionRepository;
import com.loadpilot.backend.service.AuditLogService;
import com.loadpilot.backend.service.ScheduledExecutionService;
import com.loadpilot.backend.service.execution.ScheduleNextRunCalculator;
import com.loadpilot.backend.service.execution.TriggerClaim;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * P1-B — CRUD + cycle de vie d'une ScheduledExecution. Ne declenche JAMAIS
 * d'Execution elle-meme en dehors de runNow() (voir ScheduledExecutionTrigger
 * et ScheduledExecutionPoller pour le declenchement automatique) - cette
 * classe ne fait QUE persister la configuration et calculer "nextRunAt".
 */
@Service
@RequiredArgsConstructor
public class ScheduledExecutionServiceImpl implements ScheduledExecutionService {

    private final ScheduledExecutionRepository scheduledExecutionRepository;
    private final ScenarioRepository scenarioRepository;
    private final AppUserRepository appUserRepository;
    private final ScheduledExecutionMapper scheduledExecutionMapper;
    private final AuditLogService auditLogService;
    private final ScheduledExecutionTransactionHelper transactionHelper;
    private final ScheduledExecutionTrigger trigger;

    @Override
    @Transactional
    public ScheduledExecutionResponse create(ScheduledExecutionRequest request, UUID createdByAppUserId) {
        try {
            var scenario = scenarioRepository.findById(request.scenarioId())
                    .orElseThrow(() -> new ResourceNotFoundException("Scenario introuvable : " + request.scenarioId()));
            AppUser createdBy = appUserRepository.getReferenceById(createdByAppUserId);

            Instant now = Instant.now();
            Instant nextRunAt = ScheduleNextRunCalculator.computeInitial(
                    request.scheduleType(), request.runAt(), request.cronExpression(), request.timezone(), now);

            ScheduledExecution schedule = ScheduledExecution.builder()
                    .scenario(scenario)
                    .name(request.name())
                    .scheduleType(request.scheduleType())
                    .cronExpression(request.cronExpression())
                    .runAt(request.runAt())
                    .timezone(request.timezone())
                    .nextRunAt(nextRunAt)
                    .enabled(true)
                    .createdBy(createdBy)
                    .build();
            ScheduledExecution saved = scheduledExecutionRepository.save(schedule);

            auditLogService.record(AuditAction.CREATE, AuditModule.SCHEDULING, AuditResult.SUCCESS,
                    "Scheduled execution created: " + saved.getId() + " for scenario " + scenario.getId());
            return toResponseWithScenarioAndApplication(saved.getId());
        } catch (RuntimeException e) {
            auditLogService.record(AuditAction.CREATE, AuditModule.SCHEDULING, AuditResult.FAILURE,
                    "Scheduled execution creation failed for scenario " + request.scenarioId() + ": " + e.getClass().getSimpleName());
            throw e;
        }
    }

    @Override
    @Transactional
    public ScheduledExecutionResponse update(UUID id, ScheduledExecutionRequest request) {
        try {
            ScheduledExecution schedule = scheduledExecutionRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Planification introuvable : " + id));
            var scenario = scenarioRepository.findById(request.scenarioId())
                    .orElseThrow(() -> new ResourceNotFoundException("Scenario introuvable : " + request.scenarioId()));

            Instant now = Instant.now();
            Instant nextRunAt = ScheduleNextRunCalculator.computeInitial(
                    request.scheduleType(), request.runAt(), request.cronExpression(), request.timezone(), now);

            schedule.setScenario(scenario);
            schedule.setName(request.name());
            schedule.setScheduleType(request.scheduleType());
            schedule.setCronExpression(request.cronExpression());
            schedule.setRunAt(request.runAt());
            schedule.setTimezone(request.timezone());
            // Une modification recalcule toujours "nextRunAt" depuis la
            // NOUVELLE configuration (jamais l'ancienne valeur figee) -
            // reactive de facto une planification desactivee dont la config
            // a change ? NON : "enabled" n'est jamais touche ici (voir
            // setEnabled), une modification de configuration seule ne
            // reactive jamais silencieusement une planification en pause.
            schedule.setNextRunAt(nextRunAt);

            ScheduledExecution saved = scheduledExecutionRepository.save(schedule);
            auditLogService.record(AuditAction.UPDATE, AuditModule.SCHEDULING, AuditResult.SUCCESS,
                    "Scheduled execution updated: " + id);
            return toResponseWithScenarioAndApplication(saved.getId());
        } catch (RuntimeException e) {
            auditLogService.record(AuditAction.UPDATE, AuditModule.SCHEDULING, AuditResult.FAILURE,
                    "Scheduled execution update failed for id " + id + ": " + e.getClass().getSimpleName());
            throw e;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ScheduledExecutionResponse> list(UUID scenarioId) {
        List<ScheduledExecution> schedules = scenarioId != null
                ? scheduledExecutionRepository.findByScenarioIdWithScenarioAndApplication(scenarioId)
                : scheduledExecutionRepository.findAllWithScenarioAndApplication();
        return scheduledExecutionMapper.toResponseList(schedules);
    }

    @Override
    @Transactional(readOnly = true)
    public ScheduledExecutionResponse getById(UUID id) {
        return toResponseWithScenarioAndApplication(id);
    }

    @Override
    @Transactional
    public ScheduledExecutionResponse setEnabled(UUID id, boolean enabled) {
        ScheduledExecution schedule = scheduledExecutionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Planification introuvable : " + id));
        schedule.setEnabled(enabled);
        if (enabled && schedule.getNextRunAt() == null && schedule.getScheduleType() == com.loadpilot.backend.enums.ScheduleType.RECURRING_CRON) {
            // Reactivation d'une RECURRING_CRON dont "nextRunAt" avait ete
            // laisse null (ex: cron invalide historique corrige par un
            // update entre-temps, cas limite) - recalcule depuis maintenant
            // plutot que de rester silencieusement inerte pour toujours.
            schedule.setNextRunAt(ScheduleNextRunCalculator.computeAfterTrigger(schedule, Instant.now()));
        }
        scheduledExecutionRepository.save(schedule);
        auditLogService.record(enabled ? AuditAction.ENABLE : AuditAction.DISABLE, AuditModule.SCHEDULING, AuditResult.SUCCESS,
                "Scheduled execution " + id + (enabled ? " enabled" : " disabled"));
        return toResponseWithScenarioAndApplication(id);
    }

    @Override
    public ScheduledExecutionResponse runNow(UUID id) {
        TriggerClaim claim = transactionHelper.claimForManualRun(id);
        trigger.fire(claim, true);
        return toResponseWithScenarioAndApplication(id);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        ScheduledExecution schedule = scheduledExecutionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Planification introuvable : " + id));
        scheduledExecutionRepository.delete(schedule);
        auditLogService.record(AuditAction.DELETE, AuditModule.SCHEDULING, AuditResult.SUCCESS,
                "Scheduled execution deleted: " + id);
    }

    /**
     * Volontairement SANS @Transactional propre : toujours appelee soit
     * depuis une methode DEJA transactionnelle de cette classe (l'appel
     * this.methode() ignorerait de toute facon l'annotation - meme piege
     * documente pour ExecutionTransactionHelper), soit depuis runNow()
     * (sans transaction ambiante) - dans les deux cas, le JOIN FETCH de
     * findByIdWithScenarioAndApplication charge tout ce dont le mapper a
     * besoin de maniere eager, et Spring Data ouvre de toute facon sa
     * propre transaction courte pour CETTE requete meme sans transaction
     * ambiante (SimpleJpaRepository) - jamais de risque de
     * LazyInitializationException ici.
     */
    private ScheduledExecutionResponse toResponseWithScenarioAndApplication(UUID id) {
        ScheduledExecution schedule = scheduledExecutionRepository.findByIdWithScenarioAndApplication(id)
                .orElseThrow(() -> new ResourceNotFoundException("Planification introuvable : " + id));
        return scheduledExecutionMapper.toResponse(schedule);
    }
}
