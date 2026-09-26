package com.loadpilot.backend.enums;

/**
 * Type d'une ScheduledExecution (P1-B) - voir ScheduledExecutionPoller pour
 * le calcul reel de la prochaine occurrence de chaque type.
 *
 * ONE_TIME : se declenche EXACTEMENT une fois, a la date/heure absolue
 * "runAt" ; "nextRunAt" repasse a null apres declenchement (automatique OU
 * "run now") - ne se reprogramme jamais tout seul.
 *
 * RECURRING_CRON : expression cron standard (5 champs, evaluee via
 * {@link org.springframework.scheduling.support.CronExpression} - accepte
 * en realite aussi la syntaxe etendue Spring a 6 champs avec secondes),
 * interpretee dans le fuseau horaire "timezone" de la ScheduledExecution ;
 * "nextRunAt" est recalcule a CHAQUE declenchement (automatique OU
 * "run now"), jamais figee.
 */
public enum ScheduleType {
    ONE_TIME,
    RECURRING_CRON
}
