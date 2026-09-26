package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.dto.response.ExecutionResponse;
import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.enums.NotificationType;
import com.loadpilot.backend.repository.AppUserRepository;
import com.loadpilot.backend.service.AuditLogService;
import com.loadpilot.backend.service.ExecutionService;
import com.loadpilot.backend.service.NotificationService;
import com.loadpilot.backend.service.execution.TriggerClaim;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Declenchement REEL, PARTAGE, d'une ScheduledExecution deja reclamee (voir
 * ScheduledExecutionTransactionHelper) - UNIQUE point d'appel de
 * ExecutionService.executeScheduled pour un declenchement de planification,
 * utilise A LA FOIS par ScheduledExecutionServiceImpl#runNow ET
 * ScheduledExecutionPoller : jamais deux chemins de declenchement qui
 * pourraient diverger (notifier/auditer differemment selon l'appelant).
 *
 * Ne fait AUCUNE hypothese sur l'absorption de l'exception : elle est
 * TOUJOURS re-levee apres avoir ete notifiee/auditee - c'est a l'appelant
 * de decider (runNow la laisse remonter en reponse HTTP ; le poller
 * l'absorbe, voir sa Javadoc, pour ne jamais crasher un poll tick entier
 * a cause d'UNE planification en echec).
 */
@Service
@RequiredArgsConstructor
public class ScheduledExecutionTrigger {

    private static final int ERROR_MAX_LENGTH = 500;

    private final ExecutionService executionService;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;
    private final AppUserRepository appUserRepository;
    private final ScheduledExecutionTransactionHelper transactionHelper;

    public void fire(TriggerClaim claim, boolean manual) {
        AppUser actor = appUserRepository.findById(claim.createdByAppUserId()).orElse(null);
        String origin = manual ? " (run now)" : " (automatic)";
        try {
            ExecutionResponse response = executionService.executeScheduled(
                    claim.scenarioId(), claim.createdByAppUserId(), claim.scheduleId());
            UUID executionId = UUID.fromString(response.id());

            transactionHelper.recordTriggerOutcome(claim.scheduleId(), executionId, null);
            notificationService.create(claim.createdByAppUserId(), NotificationType.SCHEDULE_TRIGGERED,
                    "Planification déclenchée",
                    "La planification \"" + claim.scheduleName() + "\" a démarré l'exécution " + executionId + ".",
                    executionId, claim.scheduleId());
            auditLogService.record(actor, AuditAction.TRIGGER, AuditModule.SCHEDULING, AuditResult.SUCCESS,
                    "Scheduled execution " + claim.scheduleId() + " triggered execution " + executionId + origin);
        } catch (RuntimeException e) {
            String error = truncate(e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : ""));

            transactionHelper.recordTriggerOutcome(claim.scheduleId(), null, error);
            notificationService.create(claim.createdByAppUserId(), NotificationType.SCHEDULE_FAILED,
                    "Échec de déclenchement de planification",
                    "La planification \"" + claim.scheduleName() + "\" n'a pas pu démarrer d'exécution : " + error,
                    null, claim.scheduleId());
            auditLogService.record(actor, AuditAction.TRIGGER, AuditModule.SCHEDULING, AuditResult.FAILURE,
                    "Scheduled execution " + claim.scheduleId() + " failed to trigger" + origin + ": " + error);
            throw e;
        }
    }

    private static String truncate(String s) {
        return s != null && s.length() > ERROR_MAX_LENGTH ? s.substring(0, ERROR_MAX_LENGTH) : s;
    }
}
