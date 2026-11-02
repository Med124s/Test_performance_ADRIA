package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.service.execution.TriggerClaim;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Declenche les planifications dues : interrogation periodique de la base (app.scheduler.poll-interval-ms). */
@Component
@RequiredArgsConstructor
public class ScheduledExecutionPoller {

    private static final Logger log = LoggerFactory.getLogger(ScheduledExecutionPoller.class);

    private final com.loadpilot.backend.repository.ScheduledExecutionRepository scheduledExecutionRepository;
    private final ScheduledExecutionTransactionHelper transactionHelper;
    private final ScheduledExecutionTrigger trigger;

    @org.springframework.beans.factory.annotation.Value("${app.scheduler.missed-grace-seconds:120}")
    private long missedGraceSeconds = 120;

    @Scheduled(fixedDelayString = "${app.scheduler.poll-interval-ms:5000}")
    public void pollAndTrigger() {
        Instant now = Instant.now();
        List<UUID> dueIds;
        try {
            dueIds = scheduledExecutionRepository.findDueScheduleIds(now);
        } catch (RuntimeException e) {
            log.warn("Lecture des planifications dues echouee : {}", e.getClass().getSimpleName());
            return;
        }

        for (UUID id : dueIds) {
            try {
                TriggerClaim claim = transactionHelper.claimForAutomaticTrigger(id, now, java.time.Duration.ofSeconds(missedGraceSeconds));
                if (claim.missedCount() > 0) {
                    trigger.notifyMissed(claim);
                    continue;
                }
                if (!claim.claimed()) {
                    // Deja declenchee ou modifiee entre-temps.
                    continue;
                }
                trigger.fire(claim, false);
            } catch (RuntimeException e) {
                // Un echec n'empeche pas le traitement des autres planifications.
                log.warn("Declenchement automatique de la planification {} echoue : {}", id, e.getClass().getSimpleName());
            }
        }
    }
}
