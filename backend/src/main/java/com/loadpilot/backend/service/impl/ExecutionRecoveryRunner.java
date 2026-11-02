package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.service.audit.AuditEvent;
import com.loadpilot.backend.service.audit.AuditTargetType;
import com.loadpilot.backend.service.audit.AuditTargets;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.enums.NotificationType;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.service.AuditLogService;
import com.loadpilot.backend.service.NotificationService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Au demarrage, passe en FAILED les executions restees en attente ou en cours lors de l'arret du backend, en conservant leurs resultats deja ecrits. */
@Component
@RequiredArgsConstructor
public class ExecutionRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ExecutionRecoveryRunner.class);
    private static final String RECOVERY_REASON = "Backend restarted while execution was running";

    private final ExecutionRepository executionRepository;
    private final com.loadpilot.backend.repository.ExecutionStepResultRepository resultRepository;
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Execution> orphaned = executionRepository.findByStatusIn(
                List.of(ExecutionStatus.QUEUED, ExecutionStatus.RUNNING, ExecutionStatus.PAUSED));

        if (orphaned.isEmpty()) {
            log.info("Recuperation au demarrage : aucune execution orpheline (QUEUED/RUNNING) trouvee.");
            return;
        }

        Instant now = Instant.now();
        for (Execution execution : orphaned) {
            // Resultats ecrits pendant le test : conserves et comptes.
            long successful = resultRepository.countByExecutionIdAndSuccess(execution.getId(), true);
            long failed = resultRepository.countByExecutionIdAndSuccess(execution.getId(), false);
            execution.setSuccessfulSteps((int) successful);
            execution.setFailedSteps((int) failed);
            execution.setStatus(ExecutionStatus.FAILED);
            execution.setErrorMessage(recoveryMessage(successful + failed));
            execution.setFinishedAt(now);
            execution.setDuration(Duration.between(execution.getStartedAt(), now).toMillis());
            executionRepository.save(execution);

            auditLogService.record(AuditEvent.of(AuditAction.FINISH, AuditModule.EXECUTION)
                    .target(AuditTargetType.EXECUTION, execution.getId(), AuditTargets.executionLabel(execution))
                    .system("Redémarrage du serveur")
                    .result(AuditResult.FAILURE)
                    .description("Exécution interrompue par un redémarrage du serveur : marquée échouée"));

            // Notifie l'utilisateur a l'origine de l'execution.
            UUID triggeredByAppUserId = execution.getTriggeredBy() != null ? execution.getTriggeredBy().getId() : null;
            notificationService.create(triggeredByAppUserId, NotificationType.EXECUTION_FAILED,
                    "Exécution échouée (redémarrage serveur)",
                    "L'exécution " + execution.getId() + " a été marquée en échec car le serveur a redémarré pendant son déroulement.",
                    execution.getId(), null);
        }

        log.warn("Recuperation au demarrage : {} execution(s) orpheline(s) marquee(s) FAILED ({}).",
                orphaned.size(), RECOVERY_REASON);
    }

    /** Motif d'echec ; avec des resultats partiels, leur nombre. */
    static String recoveryMessage(long keptResults) {
        if (keptResults <= 0) return RECOVERY_REASON;
        return RECOVERY_REASON + " (" + java.text.NumberFormat.getIntegerInstance(java.util.Locale.FRANCE).format(keptResults)
                + " résultat(s) enregistré(s) avant l'interruption, conservé(s))";
    }
}
