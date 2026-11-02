package com.loadpilot.backend.enums;

/** Type de notification. */
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
