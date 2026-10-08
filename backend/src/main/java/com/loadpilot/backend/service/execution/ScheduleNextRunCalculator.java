package com.loadpilot.backend.service.execution;

import com.loadpilot.backend.entity.ScheduledExecution;
import com.loadpilot.backend.enums.ScheduleType;
import com.loadpilot.backend.exception.InvalidScheduleException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.scheduling.support.CronExpression;

/**
 * Calcul REEL (jamais approximatif) de la prochaine occurrence d'une
 * ScheduledExecution - partage par ScheduledExecutionServiceImpl (creation/
 * modification/validation/apercu) et ScheduledExecutionTransactionHelper
 * (reprogrammation apres chaque declenchement), pour ne jamais dupliquer
 * cette logique a deux endroits qui pourraient diverger.
 *
 * CHOIX TECHNOLOGIQUE (P1-B) : cron via {@link CronExpression} (deja inclus
 * dans spring-context, AUCUNE nouvelle dependance comme Quartz). Les
 * frequences de l'interface (chaque jour, jours ouvres, chaque semaine,
 * chaque mois) sont toutes traduites en expression cron : une seule
 * mecanique de calcul. Une expression standard a 5 champs (minute heure
 * jour mois jour-semaine) est acceptee et completee par "0" secondes.
 *
 * TIMEZONE : le fuseau horaire de la ScheduledExecution n'intervient QUE
 * pour interpreter l'heure de mur de "cronExpression" - le resultat est
 * toujours converti en Instant (UTC) avant d'etre compare/persiste : les
 * changements d'heure ete/hiver du fuseau choisi sont donc geres
 * correctement a chaque recalcul.
 *
 * DATE DE FIN ("endAt", recurrente seulement) : aucune occurrence apres
 * cet instant - la prochaine occurrence devient alors null (planification
 * expiree).
 */
public final class ScheduleNextRunCalculator {

    private ScheduleNextRunCalculator() {
    }

    /**
     * Expression cron normalisee (6 champs Spring) : espaces multiples reduits, et "0" secondes ajoute
     * devant une expression standard a 5 champs. Null ou vide reste tel quel (refuse plus loin).
     */
    public static String normalizeCron(String cronExpression) {
        if (cronExpression == null || cronExpression.isBlank()) return cronExpression;
        String compact = cronExpression.trim().replaceAll("\\s+", " ");
        return compact.split(" ").length == 5 ? "0 " + compact : compact;
    }

    /** Calcul initial, a la creation / modification - valide aussi "runAt" (unique : strictement futur) et "endAt". */
    public static Instant computeInitial(ScheduleType type, Instant runAt, String cronExpression,
                                          String timezone, Instant now) {
        return computeInitial(type, runAt, cronExpression, timezone, null, now);
    }

    public static Instant computeInitial(ScheduleType type, Instant runAt, String cronExpression,
                                          String timezone, Instant endAt, Instant now) {
        ZoneId zone = parseZone(timezone);
        return switch (type) {
            case ONE_TIME -> {
                if (runAt == null) {
                    throw new InvalidScheduleException("Choisissez la date et l'heure du lancement.");
                }
                if (!runAt.isAfter(now)) {
                    throw new InvalidScheduleException("La date et l'heure du lancement sont déjà passées : choisissez un moment dans le futur.");
                }
                yield runAt;
            }
            case RECURRING_CRON -> {
                if (endAt != null && !endAt.isAfter(now)) {
                    throw new InvalidScheduleException("La date de fin est déjà passée.");
                }
                Instant next = nextCronOccurrence(cronExpression, zone, now);
                if (endAt != null && next.isAfter(endAt)) {
                    throw new InvalidScheduleException("La date de fin est avant le premier lancement : aucune exécution ne serait faite.");
                }
                yield next;
            }
        };
    }

    /** Recalcul APRES un declenchement (automatique ou "run now") : unique -> null (terminee) ; recurrente ->
     * occurrence suivante apres "now", ou null si elle depasse la date de fin (expiree). */
    public static Instant computeAfterTrigger(ScheduledExecution schedule, Instant now) {
        return switch (schedule.getScheduleType()) {
            case ONE_TIME -> null;
            case RECURRING_CRON -> {
                Instant next = nextCronOccurrence(schedule.getCronExpression(), parseZone(schedule.getTimezone()), now);
                yield schedule.getEndAt() != null && next.isAfter(schedule.getEndAt()) ? null : next;
            }
        };
    }

    /** Occurrences (recurrente) strictement apres "from" et au plus tard a "until" (inclus), au plus "max". */
    public static List<Instant> occurrencesBetween(ScheduledExecution schedule, Instant from, Instant until, int max) {
        List<Instant> out = new ArrayList<>();
        if (schedule.getScheduleType() != ScheduleType.RECURRING_CRON) return out;
        CronExpression parsed = parse(schedule.getCronExpression());
        ZoneId zone = parseZone(schedule.getTimezone());
        ZonedDateTime cursor = from.atZone(zone);
        while (out.size() < max) {
            ZonedDateTime next = parsed.next(cursor);
            if (next == null || next.toInstant().isAfter(until)) break;
            out.add(next.toInstant());
            cursor = next;
        }
        return out;
    }

    /** Apercu : les "count" prochaines occurrences a partir de "now" (unique : la date elle-meme). */
    public static List<Instant> preview(ScheduleType type, Instant runAt, String cronExpression, String timezone,
                                        Instant endAt, Instant now, int count) {
        Instant first = computeInitial(type, runAt, cronExpression, timezone, endAt, now);
        List<Instant> out = new ArrayList<>();
        out.add(first);
        if (type == ScheduleType.ONE_TIME) return out;
        CronExpression parsed = parse(cronExpression);
        ZoneId zone = parseZone(timezone);
        ZonedDateTime cursor = first.atZone(zone);
        while (out.size() < count) {
            ZonedDateTime next = parsed.next(cursor);
            if (next == null || (endAt != null && next.toInstant().isAfter(endAt))) break;
            out.add(next.toInstant());
            cursor = next;
        }
        return out;
    }

    private static Instant nextCronOccurrence(String cronExpression, ZoneId zone, Instant now) {
        ZonedDateTime next = parse(cronExpression).next(now.atZone(zone));
        if (next == null) {
            throw new InvalidScheduleException("Cette expression CRON n'a plus aucune occurrence future.");
        }
        return next.toInstant();
    }

    private static CronExpression parse(String cronExpression) {
        if (cronExpression == null || cronExpression.isBlank()) {
            throw new InvalidScheduleException("Renseignez une expression CRON pour une planification récurrente.");
        }
        try {
            return CronExpression.parse(normalizeCron(cronExpression));
        } catch (IllegalArgumentException e) {
            throw new InvalidScheduleException("Expression CRON invalide : vérifiez les 5 champs (minute heure jour mois jour-de-semaine), "
                    + "par exemple « 0 2 * * * » pour chaque jour à 02:00.");
        }
    }

    public static ZoneId parseZone(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException | NullPointerException e) {
            throw new InvalidScheduleException("Fuseau horaire invalide : \"" + timezone + "\" (attendu un identifiant IANA, ex: Africa/Casablanca, UTC).");
        }
    }
}
