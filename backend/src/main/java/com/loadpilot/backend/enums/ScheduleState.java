package com.loadpilot.backend.enums;

/** Etat calcule d'une planification : ACTIVE, PAUSED, SUSPENDED (scenario inactif ou archive) ou COMPLETED ou EXPIRED. */
public enum ScheduleState {
    ACTIVE,
    PAUSED,
    SUSPENDED,
    COMPLETED,
    EXPIRED
}
