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
                .hasMessageContaining("date et l'heure");
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
                .hasMessageContaining("CRON invalide");
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

    // ---- frequences de l'interface (toutes traduites en CRON), CRON standard, fuseau, date de fin

    @Test
    void normalizeCron_acceptsStandardFiveFieldExpressions() {
        assertThat(ScheduleNextRunCalculator.normalizeCron("0 2 * * *")).isEqualTo("0 0 2 * * *");
        assertThat(ScheduleNextRunCalculator.normalizeCron("  */5   * * * * ")).isEqualTo("0 */5 * * * *");
        assertThat(ScheduleNextRunCalculator.normalizeCron("0 0 2 * * MON-FRI")).isEqualTo("0 0 2 * * MON-FRI");
    }

    @Test
    void frequencies_daily_weekdays_weekly_monthly() {
        // now = jeudi 15 janvier 2026, 10:00 UTC
        java.util.List<Instant> daily = ScheduleNextRunCalculator.preview(ScheduleType.RECURRING_CRON, null, "0 2 * * *", "UTC", null, now, 3);
        assertThat(daily).containsExactly(Instant.parse("2026-01-16T02:00:00Z"), Instant.parse("2026-01-17T02:00:00Z"),
                Instant.parse("2026-01-18T02:00:00Z"));
        // Jours ouvres : vendredi 16, lundi 19, mardi 20.
        java.util.List<Instant> weekdays = ScheduleNextRunCalculator.preview(ScheduleType.RECURRING_CRON, null, "0 0 2 * * MON-FRI", "UTC", null, now, 3);
        assertThat(weekdays).containsExactly(Instant.parse("2026-01-16T02:00:00Z"), Instant.parse("2026-01-19T02:00:00Z"),
                Instant.parse("2026-01-20T02:00:00Z"));
        // Chaque semaine, lundi et mercredi a 18:30.
        java.util.List<Instant> weekly = ScheduleNextRunCalculator.preview(ScheduleType.RECURRING_CRON, null, "0 30 18 * * MON,WED", "UTC", null, now, 3);
        assertThat(weekly).containsExactly(Instant.parse("2026-01-19T18:30:00Z"), Instant.parse("2026-01-21T18:30:00Z"),
                Instant.parse("2026-01-26T18:30:00Z"));
        // Chaque mois, le 31 : les mois plus courts sont sautes (fevrier).
        java.util.List<Instant> monthly = ScheduleNextRunCalculator.preview(ScheduleType.RECURRING_CRON, null, "0 0 3 31 * *", "UTC", null, now, 3);
        assertThat(monthly).containsExactly(Instant.parse("2026-01-31T03:00:00Z"), Instant.parse("2026-03-31T03:00:00Z"),
                Instant.parse("2026-05-31T03:00:00Z"));
    }

    @Test
    void timezone_wallClockIsKeptAcrossDaylightSavingChange() {
        // Paris passe a l'heure d'ete le 29 mars 2026 : 02:30 locales n'existent pas ce jour-la,
        // et 08:00 locales valent 07:00 UTC avant, 06:00 UTC apres.
        Instant before = Instant.parse("2026-03-27T12:00:00Z");
        java.util.List<Instant> runs = ScheduleNextRunCalculator.preview(ScheduleType.RECURRING_CRON, null, "0 8 * * *", "Europe/Paris", null, before, 4);
        assertThat(runs).containsExactly(Instant.parse("2026-03-28T07:00:00Z"), Instant.parse("2026-03-29T06:00:00Z"),
                Instant.parse("2026-03-30T06:00:00Z"), Instant.parse("2026-03-31T06:00:00Z"));
    }

    @Test
    void endAt_stopsOccurrences_andIsValidated() {
        Instant endAt = Instant.parse("2026-01-17T12:00:00Z");
        assertThat(ScheduleNextRunCalculator.preview(ScheduleType.RECURRING_CRON, null, "0 2 * * *", "UTC", endAt, now, 3))
                .containsExactly(Instant.parse("2026-01-16T02:00:00Z"), Instant.parse("2026-01-17T02:00:00Z"));
        ScheduledExecution schedule = ScheduledExecution.builder().scheduleType(ScheduleType.RECURRING_CRON)
                .cronExpression("0 0 2 * * *").timezone("UTC").endAt(endAt).build();
        assertThat(ScheduleNextRunCalculator.computeAfterTrigger(schedule, Instant.parse("2026-01-17T02:00:01Z"))).isNull();
        assertThatThrownBy(() -> ScheduleNextRunCalculator.computeInitial(ScheduleType.RECURRING_CRON, null, "0 2 * * *", "UTC",
                Instant.parse("2026-01-15T20:00:00Z"), now))
                .isInstanceOf(InvalidScheduleException.class).hasMessageContaining("date de fin est avant le premier lancement");
        assertThatThrownBy(() -> ScheduleNextRunCalculator.computeInitial(ScheduleType.RECURRING_CRON, null, "0 2 * * *", "UTC",
                now.minusSeconds(1), now))
                .isInstanceOf(InvalidScheduleException.class).hasMessageContaining("date de fin est déjà passée");
    }

    @Test
    void occurrencesBetween_listsMissedRuns_withCap() {
        ScheduledExecution schedule = ScheduledExecution.builder().scheduleType(ScheduleType.RECURRING_CRON)
                .cronExpression("0 */5 * * * *").timezone("UTC").build();
        java.util.List<Instant> missed = ScheduleNextRunCalculator.occurrencesBetween(schedule, now, now.plus(1, ChronoUnit.HOURS), 100);
        assertThat(missed).hasSize(12).first().isEqualTo(now.plus(5, ChronoUnit.MINUTES));
        assertThat(ScheduleNextRunCalculator.occurrencesBetween(schedule, now, now.plus(1, ChronoUnit.DAYS), 19)).hasSize(19);
    }

    @Test
    void oneTime_previewIsTheDateItself() {
        Instant runAt = now.plus(2, ChronoUnit.MINUTES);
        assertThat(ScheduleNextRunCalculator.preview(ScheduleType.ONE_TIME, runAt, null, "UTC", null, now, 3)).containsExactly(runAt);
    }
}