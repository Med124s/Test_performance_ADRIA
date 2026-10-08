package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.entity.ScheduledExecution;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.enums.NotificationType;
import com.loadpilot.backend.enums.ScheduleNotifyMode;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.ScheduledExecutionRepository;
import com.loadpilot.backend.service.NotificationService;
import com.loadpilot.backend.service.execution.ScheduleNextRunCalculator;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Notifications propres aux planifications :
 * - fin d'une execution planifiee, selon le reglage de la planification (ALWAYS : chaque fin ;
 *   PROBLEMS : echec ou regression seulement) : « Test nocturne : réussi · P95 314 ms »,
 *   « … : échoué · 5 % d'erreurs », « … : régression · P95 +35 % par rapport à l'exécution précédente »
 *   (meme seuil que le Dashboard, DashboardRules.REGRESSION_PERCENT) ; lien vers le rapport
 *   (relatedExecutionId) ;
 * - lancement non fait (ignore, manque, refuse) : toujours notifie, quel que soit le reglage.
 */
@Service
@RequiredArgsConstructor
public class ScheduleNotifier {

    /** Ce qu'il faut notifier a la fin d'une execution planifiee (null type = rien). */
    public record Decision(NotificationType type, String title) {
    }

    private final NotificationService notificationService;
    private final ScheduledExecutionRepository scheduleRepository;
    private final ExecutionRepository executionRepository;
    private final ExecutionStatsService statsService;
    private final EntityManager entityManager;

    /** Fin d'une execution planifiee (statut terminal deja enregistre). */
    @Transactional
    public void executionFinished(UUID scheduleId, UUID executionId, UUID recipientAppUserId) {
        Execution execution = executionRepository.findById(executionId).orElse(null);
        if (execution == null) return;
        ensureStats(execution);
        ScheduledExecution schedule = scheduleRepository.findById(scheduleId).orElse(null);
        String name = schedule != null ? schedule.getName() : execution.getScenario().getName();
        ScheduleNotifyMode mode = schedule != null ? schedule.getNotifyMode() : ScheduleNotifyMode.ALWAYS;

        List<Execution> previous = executionRepository.findPreviousFinishedBySchedule(scheduleId, execution.getStartedAt(), PageRequest.of(0, 1));
        Execution prev = previous.isEmpty() ? null : previous.get(0);
        if (prev != null) ensureStats(prev);
        Long prevP95 = prev == null ? null : prev.getP95Ms();
        Double change = DashboardRules.changePercent(execution.getP95Ms(), prevP95);
        Double errorRate = errorRatePercent(execution);

        Decision decision = decide(name, execution.getStatus(), execution.getP95Ms(), errorRate, change, mode);
        if (decision.type() == null) return;
        notificationService.create(recipientAppUserId, decision.type(), decision.title(),
                message(execution, errorRate, prevP95), executionId, scheduleId);
    }

    /** Lancement non fait : toujours notifie (le reglage ne concerne que la fin des executions). */
    public void notRun(UUID recipientAppUserId, UUID scheduleId, String title, String message) {
        notificationService.create(recipientAppUserId, NotificationType.SCHEDULE_FAILED, title, message, null, scheduleId);
    }

    /** Regle de notification de fin, sans acces a la base (testee directement). */
    public static Decision decide(String name, ExecutionStatus status, Long p95Ms, Double errorRatePercent,
                                  Double p95ChangePercent, ScheduleNotifyMode mode) {
        if (status == ExecutionStatus.FAILED) {
            String rate = errorRatePercent == null ? "" : " · " + DashboardRules.format(errorRatePercent) + " % d'erreurs";
            return new Decision(NotificationType.EXECUTION_FAILED, name + " : échoué" + rate);
        }
        if (status == ExecutionStatus.THRESHOLDS_EXCEEDED) {
            // Test allé au bout mais seuil de réussite dépassé : toujours notifié (c'est un problème).
            String rate = errorRatePercent == null ? "" : " · " + DashboardRules.format(errorRatePercent) + " % d'erreurs";
            return new Decision(NotificationType.EXECUTION_THRESHOLDS_EXCEEDED, name + " : seuils dépassés" + rate);
        }
        if (status == ExecutionStatus.CANCELLED) {
            return mode == ScheduleNotifyMode.ALWAYS
                    ? new Decision(NotificationType.EXECUTION_CANCELLED, name + " : annulé")
                    : new Decision(null, null);
        }
        if (status != ExecutionStatus.SUCCESS) return new Decision(null, null);
        if (DashboardRules.isRegression(p95ChangePercent)) {
            return new Decision(NotificationType.SCHEDULE_REGRESSION, name + " : régression · P95 +"
                    + DashboardRules.format(p95ChangePercent) + " % par rapport à l'exécution précédente");
        }
        if (mode != ScheduleNotifyMode.ALWAYS) return new Decision(null, null);
        String p95 = p95Ms == null ? "" : " · P95 " + DashboardRules.format(p95Ms) + " ms";
        return new Decision(NotificationType.EXECUTION_SUCCESS, name + " : réussi" + p95);
    }

    /** Taux d'erreurs (%) arrondi a 0,1 ; null sans requete. */
    public static Double errorRatePercent(Execution e) {
        long ok = e.getSuccessfulSteps() == null ? 0 : e.getSuccessfulSteps();
        long ko = e.getFailedSteps() == null ? 0 : e.getFailedSteps();
        if (ok + ko == 0) return null;
        return Math.round(ko * 1000.0 / (ok + ko)) / 10.0;
    }

    /** Heure prevue lisible dans le fuseau de la planification (ex. « 07/10/2026 à 02:00 »). */
    public static String plannedLabel(Instant at, String timezone) {
        ZoneId zone;
        try {
            zone = ScheduleNextRunCalculator.parseZone(timezone);
        } catch (RuntimeException e) {
            zone = ZoneId.of("UTC");
        }
        return DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH:mm").format(at.atZone(zone));
    }

    private static String message(Execution e, Double errorRate, Long prevP95) {
        StringBuilder sb = new StringBuilder("Scénario « ").append(e.getScenario().getName()).append(" »");
        if (e.getP95Ms() != null) sb.append(" · P95 ").append(DashboardRules.format(e.getP95Ms())).append(" ms");
        if (errorRate != null) sb.append(" · ").append(DashboardRules.format(errorRate)).append(" % d'erreurs");
        if (e.getThroughputRps() != null) sb.append(" · ").append(DashboardRules.format(e.getThroughputRps())).append(" req/s");
        sb.append('.');
        if (prevP95 != null) sb.append(" Exécution précédente : P95 ").append(DashboardRules.format(prevP95)).append(" ms.");
        sb.append(" Ouvrez le rapport pour le détail.");
        return sb.toString();
    }

    private void ensureStats(Execution e) {
        if (!ExecutionStatsService.needsComputation(e)) return;
        try {
            statsService.compute(e.getId());
            entityManager.refresh(e);
        } catch (RuntimeException ex) {
            // Resume indisponible : la notification part sans P95 (jamais invente).
        }
    }
}
