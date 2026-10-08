package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.dto.response.ScheduleHistoryResponse;
import com.loadpilot.backend.dto.response.ScheduledExecutionResponse;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.entity.Scenario;
import com.loadpilot.backend.entity.ScheduledExecution;
import com.loadpilot.backend.entity.ScheduledRunEvent;
import com.loadpilot.backend.enums.ScenarioStatus;
import com.loadpilot.backend.enums.ScheduleState;
import com.loadpilot.backend.enums.ScheduleType;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.ScheduledRunEventRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Vue d'une planification pour l'interface : etat calcule (jamais stocke), derniere execution avec
 * l'ecart de P95 par rapport a la precedente, dernier lancement non fait, et historique complet.
 * Appele dans la transaction du service (les associations paresseuses sont deja chargees).
 */
@Service
@RequiredArgsConstructor
public class ScheduleViewService {

    /** Etat et raison (francais, affichee au survol). */
    public record StateView(ScheduleState state, String reason) {
    }

    private final ExecutionRepository executionRepository;
    private final ScheduledRunEventRepository runEventRepository;
    private final ExecutionStatsService statsService;
    private final EntityManager entityManager;

    /**
     * Ordre de priorite : Suspendue (scenario indisponible) > Terminee (unique deja lancee ou passee) >
     * Expiree (date de fin depassee) > En pause (choix de l'utilisateur) > Active.
     */
    public static StateView resolveState(ScheduledExecution s, Instant now) {
        Scenario scenario = s.getScenario();
        if (scenario.isArchived()) {
            return new StateView(ScheduleState.SUSPENDED, "Scénario archivé : restaurez-le pour reprendre la planification.");
        }
        if (scenario.getApplication().isArchived()) {
            return new StateView(ScheduleState.SUSPENDED, "Application archivée : restaurez-la pour reprendre la planification.");
        }
        if (scenario.getStatus() != ScenarioStatus.ACTIVE) {
            return new StateView(ScheduleState.SUSPENDED, "Scénario désactivé : la planification reprendra quand il sera réactivé.");
        }
        if (s.isPausedByScenario()) {
            return new StateView(ScheduleState.SUSPENDED, "Suspendue par son scénario : mettez-la en route avec « Reprendre ».");
        }
        if (s.getScheduleType() == ScheduleType.ONE_TIME && s.getNextRunAt() == null) {
            return new StateView(ScheduleState.COMPLETED, "Planification unique : son lancement a eu lieu (ou son heure est passée).");
        }
        if (s.getScheduleType() == ScheduleType.RECURRING_CRON
                && (s.getNextRunAt() == null || (s.getEndAt() != null && !s.getEndAt().isAfter(now)))) {
            return new StateView(ScheduleState.EXPIRED, "Date de fin dépassée : plus aucun lancement prévu.");
        }
        if (!s.isEnabled()) {
            return new StateView(ScheduleState.PAUSED, "Mise en pause : aucun lancement automatique jusqu'à la reprise.");
        }
        return new StateView(ScheduleState.ACTIVE, null);
    }

    public ScheduledExecutionResponse toResponse(ScheduledExecution s, Instant now) {
        StateView state = resolveState(s, now);
        ScheduledExecutionResponse.LastRun lastRun = lastRun(s);
        ScheduledExecutionResponse.LastEvent lastEvent = null;
        List<ScheduledRunEvent> events = runEventRepository.findByScheduleIdOrderByPlannedAtDesc(s.getId(), PageRequest.of(0, 1));
        if (!events.isEmpty()) {
            ScheduledRunEvent ev = events.get(0);
            if (lastRun == null || lastRun.startedAt() == null || ev.getPlannedAt().isAfter(lastRun.startedAt())) {
                lastEvent = new ScheduledExecutionResponse.LastEvent(ev.getOutcome(), ev.getPlannedAt(), ev.getReason());
            }
        }
        return new ScheduledExecutionResponse(
                s.getId().toString(),
                s.getScenario().getId().toString(),
                s.getScenario().getName(),
                s.getScenario().getApplication().getId().toString(),
                s.getScenario().getApplication().getName(),
                s.getName(),
                s.getScheduleType(),
                s.getCronExpression(),
                s.getRunAt(),
                s.getTimezone(),
                s.getNextRunAt(),
                s.isEnabled(),
                displayName(s),
                s.getCreatedAt(),
                s.getUpdatedAt(),
                s.getLastTriggeredAt(),
                s.getLastExecutionId() == null ? null : s.getLastExecutionId().toString(),
                s.getLastTriggerError(),
                s.getEndAt(),
                s.getNotifyMode(),
                state.state(),
                state.reason(),
                lastRun,
                lastEvent);
    }

    /** Executions lancees et lancements non faits, du plus recent au plus ancien (au plus "limit"). */
    public ScheduleHistoryResponse history(ScheduledExecution s, int limit) {
        List<Execution> executions = executionRepository.findByScheduleIdRecentFirst(s.getId(), PageRequest.of(0, limit + 1));
        List<ScheduledRunEvent> events = runEventRepository.findByScheduleIdOrderByPlannedAtDesc(s.getId(), PageRequest.of(0, limit + 1));
        boolean truncated = executions.size() > limit || events.size() > limit;

        List<ScheduleHistoryResponse.Item> items = new ArrayList<>();
        for (Execution e : executions.subList(0, Math.min(limit, executions.size()))) {
            ensureStats(e);
            long ok = e.getSuccessfulSteps() == null ? 0 : e.getSuccessfulSteps();
            long ko = e.getFailedSteps() == null ? 0 : e.getFailedSteps();
            items.add(new ScheduleHistoryResponse.Item("EXECUTION", e.getStartedAt(), e.getId().toString(), e.getStatus(),
                    e.getP95Ms(), ScheduleNotifier.errorRatePercent(e), e.getThroughputRps(), ok + ko, e.activeDurationMs(), null, null));
        }
        for (ScheduledRunEvent ev : events.subList(0, Math.min(limit, events.size()))) {
            items.add(new ScheduleHistoryResponse.Item("EVENT", ev.getPlannedAt(), null, null, null, null, null, null, null,
                    ev.getOutcome(), ev.getReason()));
        }
        items.sort(Comparator.comparing(ScheduleHistoryResponse.Item::at, Comparator.nullsLast(Comparator.reverseOrder())));
        if (items.size() > limit) {
            items = new ArrayList<>(items.subList(0, limit));
            truncated = true;
        }
        return new ScheduleHistoryResponse(s.getId().toString(), items, truncated);
    }

    private ScheduledExecutionResponse.LastRun lastRun(ScheduledExecution s) {
        List<Execution> recent = executionRepository.findByScheduleIdRecentFirst(s.getId(), PageRequest.of(0, 1));
        Execution last = !recent.isEmpty() ? recent.get(0)
                : s.getLastExecutionId() == null ? null : executionRepository.findById(s.getLastExecutionId()).orElse(null);
        if (last == null) return null;
        ensureStats(last);
        Long previousP95 = null;
        List<Execution> previous = executionRepository.findPreviousFinishedBySchedule(s.getId(), last.getStartedAt(), PageRequest.of(0, 1));
        if (!previous.isEmpty()) {
            ensureStats(previous.get(0));
            previousP95 = previous.get(0).getP95Ms();
        }
        return new ScheduledExecutionResponse.LastRun(last.getId().toString(), last.getStatus(), last.getStartedAt(),
                last.getP95Ms(), previousP95, DashboardRules.changePercent(last.getP95Ms(), previousP95),
                ScheduleNotifier.errorRatePercent(last));
    }

    private void ensureStats(Execution e) {
        if (!ExecutionStatsService.needsComputation(e)) return;
        try {
            statsService.compute(e.getId());
            entityManager.refresh(e);
        } catch (RuntimeException ex) {
            // Resume indisponible : valeurs simplement absentes (jamais inventees).
        }
    }

    private static String displayName(ScheduledExecution s) {
        return com.loadpilot.backend.mapper.MapperSupport.appUserDisplayName(s.getCreatedBy());
    }
}
