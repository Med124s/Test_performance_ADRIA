package com.loadpilot.backend.enums;

/** Module fonctionnel concerne par un AuditLog. */
public enum AuditModule {
    AUTH,
    USER,
    APPLICATION,
    SCENARIO,
    STEP,
    EXECUTION,
    METRIC,
    DASHBOARD,
    ADMIN,
    // P1-B - planification reelle d'executions (voir ScheduledExecution).
    SCHEDULING
}
