package com.loadpilot.backend.enums;

/**
 * Type d'une Notification reelle (P1-B) - genere UNIQUEMENT depuis de vrais
 * evenements metier (voir NotificationServiceImpl, ExecutionServiceImpl,
 * ExecutionRecoveryRunner, ScheduledExecutionPoller), jamais une notification
 * de demonstration.
 *
 * EXECUTION_* : etat terminal reel d'une Execution (manuelle OU issue d'une
 * ScheduledExecution - meme trio de statuts terminaux qu'ExecutionStatus,
 * volontairement pas de notification pour QUEUED/RUNNING, des etats
 * transitoires jamais "notifiables").
 *
 * SCHEDULE_TRIGGERED/SCHEDULE_FAILED : evenement propre a la planification
 * elle-meme (declenchement reussi/echoue AVANT meme qu'une Execution existe,
 * ex: limite de capacite atteinte, scenario supprime depuis) - distinct des
 * evenements EXECUTION_* qui concernent l'issue de l'Execution une fois
 * reellement creee et terminee.
 *
 * SCHEDULE_REGRESSION : execution planifiee terminee dont le P95 a augmente de plus du seuil du
 * Dashboard (DashboardRules.REGRESSION_PERCENT) par rapport a l'execution precedente de la planification.
 */
public enum NotificationType {
    EXECUTION_SUCCESS,
    /** Execution allee au bout, mais un seuil de reussite du scenario est depasse. */
    EXECUTION_THRESHOLDS_EXCEEDED,
    EXECUTION_FAILED,
    EXECUTION_CANCELLED,
    SCHEDULE_TRIGGERED,
    SCHEDULE_FAILED,
    SCHEDULE_REGRESSION
}
