package com.loadpilot.backend.service.execution;

import com.loadpilot.backend.entity.ScheduledExecution;
import com.loadpilot.backend.enums.ScheduleType;
import com.loadpilot.backend.exception.InvalidScheduleException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.scheduling.support.CronExpression;

/**
 * Calcul REEL (jamais approximatif) de la prochaine occurrence d'une
 * ScheduledExecution - partage par ScheduledExecutionServiceImpl (creation/
 * modification/validation) et ScheduledExecutionTransactionHelper
 * (reprogrammation apres chaque declenchement), pour ne jamais dupliquer
 * cette logique a deux endroits qui pourraient diverger.
 *
 * CHOIX TECHNOLOGIQUE (P1-B, section 30 du prompt) : cron via
 * {@link CronExpression} (deja inclus dans spring-context, AUCUNE nouvelle
 * dependance comme Quartz) - suffisant pour "a telle heure, tel(s)
 * jour(s)" ; voir le rapport P1-B pour la comparaison complete face a
 * Quartz (rejete : tables JobStore dediees et complexite de clustering
 * disproportionnees face au besoin reel actuel - un simple poller
 * PostgreSQL avec verrou pessimiste, deja l'idiome etabli par
 * ExecutionTransactionHelper, couvre le meme besoin avec une dependance en
 * moins).
 *
 * TIMEZONE : le fuseau horaire de la ScheduledExecution n'intervient QUE
 * pour interpreter l'heure de mur de "cronExpression" - le resultat est
 * toujours converti en Instant (UTC) avant d'etre compare/persiste (voir
 * ScheduledExecution.nextRunAt) : les changements d'heure ete/hiver du
 * fuseau choisi sont donc geres correctement a chaque recalcul (jamais un
 * simple offset fixe qui deriverait deux fois par an).
 */
public final class ScheduleNextRunCalculator {

    private ScheduleNextRunCalculator() {
    }

    /** Calcul initial, a la creation d'une ScheduledExecution - valide
     * egalement que "runAt" (ONE_TIME) est strictement dans le futur. */
    public static Instant computeInitial(ScheduleType type, Instant runAt, String cronExpression,
                                          String timezone, Instant now) {
        ZoneId zone = parseZone(timezone);
        return switch (type) {
            case ONE_TIME -> {
                if (runAt == null) {
                    throw new InvalidScheduleException("\"runAt\" est obligatoire pour une planification ONE_TIME.");
                }
                if (!runAt.isAfter(now)) {
                    throw new InvalidScheduleException("\"runAt\" doit être strictement dans le futur.");
                }
                yield runAt;
            }
            case RECURRING_CRON -> nextCronOccurrence(cronExpression, zone, now);
        };
    }

    /** Recalcul APRES un declenchement (automatique ou "run now") - une
     * ONE_TIME ne se reprogramme jamais (null = ne se declenchera plus
     * jamais automatiquement) ; une RECURRING_CRON est toujours reprogrammee
     * depuis "now" (voir ScheduledExecutionTransactionHelper). */
    public static Instant computeAfterTrigger(ScheduledExecution schedule, Instant now) {
        return switch (schedule.getScheduleType()) {
            case ONE_TIME -> null;
            case RECURRING_CRON -> nextCronOccurrence(schedule.getCronExpression(), parseZone(schedule.getTimezone()), now);
        };
    }

    private static Instant nextCronOccurrence(String cronExpression, ZoneId zone, Instant now) {
        if (cronExpression == null || cronExpression.isBlank()) {
            throw new InvalidScheduleException("\"cronExpression\" est obligatoire pour une planification RECURRING_CRON.");
        }
        CronExpression parsed;
        try {
            parsed = CronExpression.parse(cronExpression.trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidScheduleException("Expression cron invalide : " + e.getMessage());
        }
        ZonedDateTime next = parsed.next(now.atZone(zone));
        if (next == null) {
            throw new InvalidScheduleException("Cette expression cron n'a plus aucune occurrence future.");
        }
        return next.toInstant();
    }

    private static ZoneId parseZone(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw new InvalidScheduleException("Fuseau horaire invalide : \"" + timezone + "\" (attendu un identifiant IANA, ex: Africa/Casablanca, UTC).");
        }
    }
}
