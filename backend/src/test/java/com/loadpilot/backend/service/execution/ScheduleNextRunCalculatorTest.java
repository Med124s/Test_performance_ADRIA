package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loadpilot.backend.entity.ScheduledExecution;
import com.loadpilot.backend.enums.ScheduleType;
import com.loadpilot.backend.exception.InvalidScheduleException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/**
 * P1-B — tests unitaires purs (aucun contexte Spring) de la seule logique
 * de calcul de "nextRunAt" (voir sa Javadoc pour le choix technologique
 * CronExpression, deja inclus dans spring-context).
 */
class ScheduleNextRunCalculatorTest {

    private final Instant now = Instant.parse("2026-01-15T10:00:00Z");

    @Test
    void computeInitial_oneTime_withFutureRunAt_returnsRunAtUnchanged() {
        Instant runAt = now.plus(1, ChronoUnit.HOURS);
        Instant result = ScheduleNextRunCalculator.computeInitial(ScheduleType.ONE_TIME, runAt, null, "UTC", now);
        assertThat(result).isEqualTo(runAt);
    }

    @Test
    void computeInitial_oneTime_withPastRunAt_throws() {
        Instant runAt = now.minus(1, ChronoUnit.HOURS);
        assertThatThrownBy(() -> ScheduleNextRunCalculator.computeInitial(ScheduleType.ONE_TIME, runAt, null, "UTC", now))
                .isInstanceOf(InvalidScheduleException.class)
                .hasMessageContaining("futur");
    }

    @Test
    void computeInitial_oneTime_withNullRunAt_throws() {
        assertThatThrownBy(() -> ScheduleNextRunCalculator.computeInitial(ScheduleType.ONE_TIME, null, null, "UTC", now))
                .isInstanceOf(InvalidScheduleException.class)
                .hasMessageContaining("runAt");
    }

    @Test
    void computeInitial_recurringCron_validExpression_returnsFutureOccurrence() {
        // Tous les jours a 08:00 UTC - "now" est le 15 janvier a 10:00, donc
        // la prochaine occurrence reelle est le 16 janvier a 08:00.
        Instant result = ScheduleNextRunCalculator.computeInitial(ScheduleType.RECURRING_CRON, null, "0 0 8 * * *", "UTC", now);
        assertThat(result).isEqualTo(Instant.parse("2026-01-16T08:00:00Z"));
    }

    @Test
    void computeInitial_recurringCron_invalidExpression_throws() {
        assertThatThrownBy(() -> ScheduleNextRunCalculator.computeInitial(ScheduleType.RECURRING_CRON, null, "not a cron", "UTC", now))
                .isInstanceOf(InvalidScheduleException.class)
                .hasMessageContaining("cron");
    }

    @Test
    void computeInitial_recurringCron_missingExpression_throws() {
        assertThatThrownBy(() -> ScheduleNextRunCalculator.computeInitial(ScheduleType.RECURRING_CRON, null, null, "UTC", now))
                .isInstanceOf(InvalidScheduleException.class);
    }

    @Test
    void computeInitial_invalidTimezone_throws() {
        assertThatThrownBy(() -> ScheduleNextRunCalculator.computeInitial(ScheduleType.RECURRING_CRON, null, "0 0 8 * * *", "Not/AZone", now))
                .isInstanceOf(InvalidScheduleException.class)
                .hasMessageContaining("horaire");
    }

    @Test
    void computeInitial_recurringCron_differentTimezones_produceDifferentUtcInstants() {
        // 08:00 heure de Casablanca (UTC+1 en janvier, pas d'heure d'ete) !=
        // 08:00 UTC - la conversion doit reellement changer l'instant retourne.
        Instant utc = ScheduleNextRunCalculator.computeInitial(ScheduleType.RECURRING_CRON, null, "0 0 8 * * *", "UTC", now);
        Instant casablanca = ScheduleNextRunCalculator.computeInitial(ScheduleType.RECURRING_CRON, null, "0 0 8 * * *", "Africa/Casablanca", now);
        assertThat(casablanca).isNotEqualTo(utc);
    }

    @Test
    void computeAfterTrigger_oneTime_alwaysReturnsNull() {
        ScheduledExecution schedule = ScheduledExecution.builder()
                .scheduleType(ScheduleType.ONE_TIME).timezone("UTC").build();
        assertThat(ScheduleNextRunCalculator.computeAfterTrigger(schedule, now)).isNull();
    }

    @Test
    void computeAfterTrigger_recurringCron_returnsNextFutureOccurrence() {
        ScheduledExecution schedule = ScheduledExecution.builder()
                .scheduleType(ScheduleType.RECURRING_CRON).cronExpression("0 0 8 * * *").timezone("UTC").build();
        Instant next = ScheduleNextRunCalculator.computeAfterTrigger(schedule, now);
        assertThat(next).isAfter(now);
        assertThat(next).isEqualTo(Instant.parse("2026-01-16T08:00:00Z"));
    }
}
