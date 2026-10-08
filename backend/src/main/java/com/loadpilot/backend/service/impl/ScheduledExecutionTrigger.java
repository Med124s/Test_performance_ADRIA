package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.service.audit.AuditEvent;
import com.loadpilot.backend.service.audit.AuditSnapshots;
import com.loadpilot.backend.service.audit.AuditTargetType;
import com.loadpilot.backend.service.audit.AuditTargets;
import com.loadpilot.backend.dto.response.ExecutionResponse;
import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.enums.ScheduledRunOutcome;
import com.loadpilot.backend.exception.ExecutionLimitExceededException;
import com.loadpilot.backend.repository.AppUserRepository;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.ScheduledExecutionRepository;
import com.loadpilot.backend.service.AuditLogService;
import com.loadpilot.backend.service.ExecutionService;
import com.loadpilot.backend.service.execution.TriggerClaim;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Declenchement REEL, PARTAGE, d'une ScheduledExecution deja reclamee (voir
 * ScheduledExecutionTransactionHelper) - UNIQUE point d'appel de
 * ExecutionService.executeScheduled pour un declenchement de planification,
 * utilise A LA FOIS par ScheduledExecutionServiceImpl#runNow ET
 * ScheduledExecutionPoller : jamais deux chemins de declenchement qui
 * pourraient diverger.
 *
 * Declenchement AUTOMATIQUE : un lancement impossible n'est jamais une erreur silencieuse - il est
 * enregistre dans l'historique avec sa raison (ScheduledRunEvent) et notifie au proprietaire :
 * - une execution du meme scenario est encore en cours -> « Ignorée : exécution précédente encore en cours » ;
 * - limite de capacite atteinte -> « Ignorée : capacité insuffisante » ;
 * - autre refus (scenario sans etape...) -> « Échec du lancement : … » (exception re-levee pour le poller).
 *
 * Declenchement MANUEL ("run now") : l'exception remonte telle quelle en reponse HTTP (429 capacite...).
 */
@Service
@RequiredArgsConstructor
public class ScheduledExecutionTrigger {

    private static final int ERROR_MAX_LENGTH = 500;
    static final String SKIPPED_RUNNING_REASON = "Ignorée : exécution précédente encore en cours.";
    static final String SKIPPED_CAPACITY_REASON = "Ignorée : capacité insuffisante (trop de tests en cours sur LoadPilot).";

    private final ExecutionService executionService;
    private final AuditLogService auditLogService;
    private final AppUserRepository appUserRepository;
    private final ExecutionRepository executionRepository;
    private final ScheduledExecutionRepository scheduleRepository;
    private final ScheduledExecutionTransactionHelper transactionHelper;
    private final ScheduleNotifier notifier;
    private final AuditTargets auditTargets;

    public void fire(TriggerClaim claim, boolean manual) {
        AppUser actor = appUserRepository.findById(claim.createdByAppUserId()).orElse(null);
        String origin = manual ? " (run now)" : " (automatic)";

        if (!manual && executionRepository.countActiveByScenarioId(claim.scenarioId()) > 0) {
            skip(claim, actor, ScheduledRunOutcome.SKIPPED_RUNNING, SKIPPED_RUNNING_REASON,
                    "ignoré · exécution précédente encore en cours",
                    "une exécution du même scénario était encore en cours. Rien n'est lancé en double.");
            return;
        }
        try {
            ExecutionResponse response = executionService.executeScheduled(
                    claim.scenarioId(), claim.createdByAppUserId(), claim.scheduleId());
            UUID executionId = UUID.fromString(response.id());

            transactionHelper.recordTriggerOutcome(claim.scheduleId(), executionId, null);
            String label = auditTargets.executionLabel(executionId);
            auditLogService.record(triggerAudit(claim, manual)
                    .description("Exécution lancée" + (label != null ? " : " + label : "")));
        } catch (ExecutionLimitExceededException e) {
            if (manual) {
                failed(claim, actor, origin, e, false);
                throw e;
            }
            skip(claim, actor, ScheduledRunOutcome.SKIPPED_CAPACITY, SKIPPED_CAPACITY_REASON,
                    "ignoré · capacité insuffisante",
                    "la limite de capacité de LoadPilot était atteinte (trop de tests en cours).");
        } catch (RuntimeException e) {
            failed(claim, actor, origin, e, !manual);
            throw e;
        }
    }

    /** Lancements manques (LoadPilot arrete a l'heure prevue) : deja enregistres, seulement notifies. */
    public void notifyMissed(TriggerClaim claim) {
        String timezone = scheduleRepository.findById(claim.scheduleId()).map(s -> s.getTimezone()).orElse("UTC");
        String count = claim.missedCount() > 1 ? claim.missedCount() + " lancements manqués" : "lancement manqué";
        notifier.notRun(claim.createdByAppUserId(), claim.scheduleId(), claim.scheduleName() + " : " + count,
                "LoadPilot était arrêté ou en veille à l'heure prévue (" + ScheduleNotifier.plannedLabel(claim.plannedAt(), timezone)
                        + (claim.missedCount() > 1 ? " et suivantes" : "")
                        + "). Rien n'est rattrapé : le prochain lancement suit la planification.");
        auditLogService.record(triggerAudit(claim, false)
                .failed(claim.missedCount() > 1 ? claim.missedCount() + " lancements manqués (LoadPilot arrêté ou en veille)"
                        : "Lancement manqué (LoadPilot arrêté ou en veille)"));
    }

    private void skip(TriggerClaim claim, AppUser actor, ScheduledRunOutcome outcome, String reason, String shortText, String detail) {
        transactionHelper.recordSkipped(claim.scheduleId(), claim.plannedAt(), outcome, reason);
        String timezone = scheduleRepository.findById(claim.scheduleId()).map(s -> s.getTimezone()).orElse("UTC");
        notifier.notRun(claim.createdByAppUserId(), claim.scheduleId(), claim.scheduleName() + " : " + shortText,
                "Le lancement prévu le " + ScheduleNotifier.plannedLabel(claim.plannedAt(), timezone) + " n'a pas été fait : " + detail
                        + " Le prochain lancement suit la planification.");
        auditLogService.record(triggerAudit(claim, false).failed("Lancement ignoré : " + shortText.replace("ignoré · ", "")));
    }

    private void failed(TriggerClaim claim, AppUser actor, String origin, RuntimeException e, boolean recordEvent) {
        String error = truncate(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        if (recordEvent) {
            transactionHelper.recordSkipped(claim.scheduleId(), claim.plannedAt(), ScheduledRunOutcome.FAILED,
                    truncate("Échec du lancement : " + error));
        } else {
            transactionHelper.recordTriggerOutcome(claim.scheduleId(), null, error);
        }
        notifier.notRun(claim.createdByAppUserId(), claim.scheduleId(), claim.scheduleName() + " : lancement impossible",
                "La planification « " + claim.scheduleName() + " » n'a pas pu démarrer d'exécution : " + error);
        auditLogService.record(triggerAudit(claim, " (run now)".equals(origin)).description("Lancement impossible").failed(e));
    }

    private static String truncate(String s) {
        return s != null && s.length() > ERROR_MAX_LENGTH ? s.substring(0, ERROR_MAX_LENGTH) : s;
    }

    /** Declenchement d'une planification : acteur « Système » (sauf « Lancer maintenant », fait par l'utilisateur). */
    private AuditEvent triggerAudit(TriggerClaim claim, boolean manual) {
        AuditEvent event = AuditEvent.of(AuditAction.TRIGGER, AuditModule.SCHEDULING)
                .target(AuditTargetType.SCHEDULE, claim.scheduleId(), claim.scheduleName());
        return manual ? event : event.system("Planification : " + claim.scheduleName());
    }
}
