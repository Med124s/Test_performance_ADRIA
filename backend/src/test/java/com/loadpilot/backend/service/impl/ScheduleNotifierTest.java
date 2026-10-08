package com.loadpilot.backend.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.entity.Scenario;
import com.loadpilot.backend.entity.ScheduledExecution;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.enums.NotificationType;
import com.loadpilot.backend.enums.ScenarioStatus;
import com.loadpilot.backend.enums.ScheduleNotifyMode;
import com.loadpilot.backend.enums.ScheduleState;
import com.loadpilot.backend.enums.ScheduleType;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Regles pures des notifications de fin d'execution planifiee et des etats d'une planification. */
class ScheduleNotifierTest {

    @Test
    void success_failure_and_regression_titles() {
        ScheduleNotifier.Decision ok = ScheduleNotifier.decide("Test nocturne", ExecutionStatus.SUCCESS, 314L, 0.0, 3.0, ScheduleNotifyMode.ALWAYS);
        assertThat(ok.type()).isEqualTo(NotificationType.EXECUTION_SUCCESS);
        assertThat(ok.title()).isEqualTo("Test nocturne : réussi · P95 314 ms");

        ScheduleNotifier.Decision ko = ScheduleNotifier.decide("Test nocturne", ExecutionStatus.FAILED, 900L, 5.0, null, ScheduleNotifyMode.PROBLEMS);
        assertThat(ko.type()).isEqualTo(NotificationType.EXECUTION_FAILED);
        assertThat(ko.title()).isEqualTo("Test nocturne : échoué · 5 % d'erreurs");

        ScheduleNotifier.Decision regression = ScheduleNotifier.decide("Test nocturne", ExecutionStatus.SUCCESS, 424L, 0.2, 35.0, ScheduleNotifyMode.PROBLEMS);
        assertThat(regression.type()).isEqualTo(NotificationType.SCHEDULE_REGRESSION);
        assertThat(regression.title()).isEqualTo("Test nocturne : régression · P95 +35 % par rapport à l'exécution précédente");

        ScheduleNotifier.Decision decimals = ScheduleNotifier.decide("N", ExecutionStatus.FAILED, 1L, 2.5, null, ScheduleNotifyMode.ALWAYS);
        assertThat(decimals.title()).isEqualTo("N : échoué · 2,5 % d'erreurs");
    }

    @Test
    void thresholdsExceeded_isAlwaysNotified() {
        ScheduleNotifier.Decision d = ScheduleNotifier.decide("Test nocturne", ExecutionStatus.THRESHOLDS_EXCEEDED, 500L, 2.5, null,
                ScheduleNotifyMode.PROBLEMS);
        assertThat(d.type()).isEqualTo(NotificationType.EXECUTION_THRESHOLDS_EXCEEDED);
        assertThat(d.title()).isEqualTo("Test nocturne : seuils dépassés · 2,5 % d'erreurs");
    }

    @Test
    void regressionUsesTheDashboardThreshold() {
        // Seuil du Dashboard : strictement plus de 20 %.
        assertThat(ScheduleNotifier.decide("N", ExecutionStatus.SUCCESS, 120L, 0.0, 20.0, ScheduleNotifyMode.ALWAYS).type())
                .isEqualTo(NotificationType.EXECUTION_SUCCESS);
        assertThat(ScheduleNotifier.decide("N", ExecutionStatus.SUCCESS, 121L, 0.0, 20.1, ScheduleNotifyMode.ALWAYS).type())
                .isEqualTo(NotificationType.SCHEDULE_REGRESSION);
        // Amelioration : jamais une regression.
        assertThat(ScheduleNotifier.decide("N", ExecutionStatus.SUCCESS, 50L, 0.0, -60.0, ScheduleNotifyMode.ALWAYS).type())
                .isEqualTo(NotificationType.EXECUTION_SUCCESS);
    }

    @Test
    void problemsOnlyMode_silencesSuccessAndCancellation() {
        assertThat(ScheduleNotifier.decide("N", ExecutionStatus.SUCCESS, 314L, 0.0, 2.0, ScheduleNotifyMode.PROBLEMS).type()).isNull();
        assertThat(ScheduleNotifier.decide("N", ExecutionStatus.CANCELLED, null, null, null, ScheduleNotifyMode.PROBLEMS).type()).isNull();
        assertThat(ScheduleNotifier.decide("N", ExecutionStatus.CANCELLED, null, null, null, ScheduleNotifyMode.ALWAYS).title())
                .isEqualTo("N : annulé");
        // Echec : toujours notifie, meme sans requete (pas de taux).
        assertThat(ScheduleNotifier.decide("N", ExecutionStatus.FAILED, null, null, null, ScheduleNotifyMode.PROBLEMS).title())
                .isEqualTo("N : échoué");
    }

    @Test
    void plannedLabel_usesTheScheduleTimezone() {
        assertThat(ScheduleNotifier.plannedLabel(Instant.parse("2026-10-07T01:00:00Z"), "Africa/Casablanca")).isEqualTo("07/10/2026 à 02:00");
        assertThat(ScheduleNotifier.plannedLabel(Instant.parse("2026-10-07T01:00:00Z"), "UTC")).isEqualTo("07/10/2026 à 01:00");
    }

    // ---- etats

    private static ScheduledExecution schedule(ScheduleType type, ScenarioStatus scenarioStatus, boolean archived, boolean appArchived) {
        Application app = Application.builder().name("Banking").build();
        app.setArchived(appArchived);
        Scenario scenario = Scenario.builder().name("Parcours").application(app).status(scenarioStatus).build();
        scenario.setArchived(archived);
        return ScheduledExecution.builder().scenario(scenario).scheduleType(type).timezone("UTC")
                .cronExpression(type == ScheduleType.RECURRING_CRON ? "0 0 2 * * *" : null)
                .nextRunAt(Instant.now().plusSeconds(3600)).enabled(true).build();
    }

    @Test
    void states() {
        Instant now = Instant.now();
        assertThat(ScheduleViewService.resolveState(schedule(ScheduleType.RECURRING_CRON, ScenarioStatus.ACTIVE, false, false), now).state())
                .isEqualTo(ScheduleState.ACTIVE);

        ScheduledExecution paused = schedule(ScheduleType.RECURRING_CRON, ScenarioStatus.ACTIVE, false, false);
        paused.setEnabled(false);
        assertThat(ScheduleViewService.resolveState(paused, now).state()).isEqualTo(ScheduleState.PAUSED);

        ScheduleViewService.StateView inactive = ScheduleViewService.resolveState(
                schedule(ScheduleType.RECURRING_CRON, ScenarioStatus.INACTIVE, false, false), now);
        assertThat(inactive.state()).isEqualTo(ScheduleState.SUSPENDED);
        assertThat(inactive.reason()).startsWith("Scénario désactivé");
        assertThat(ScheduleViewService.resolveState(schedule(ScheduleType.RECURRING_CRON, ScenarioStatus.ACTIVE, true, false), now).reason())
                .startsWith("Scénario archivé");
        assertThat(ScheduleViewService.resolveState(schedule(ScheduleType.RECURRING_CRON, ScenarioStatus.ACTIVE, false, true), now).reason())
                .startsWith("Application archivée");

        ScheduledExecution done = schedule(ScheduleType.ONE_TIME, ScenarioStatus.ACTIVE, false, false);
        done.setNextRunAt(null);
        assertThat(ScheduleViewService.resolveState(done, now).state()).isEqualTo(ScheduleState.COMPLETED);

        ScheduledExecution expired = schedule(ScheduleType.RECURRING_CRON, ScenarioStatus.ACTIVE, false, false);
        expired.setEndAt(now.minusSeconds(1));
        expired.setEnabled(false);
        assertThat(ScheduleViewService.resolveState(expired, now).state()).isEqualTo(ScheduleState.EXPIRED);
    }
}
