package com.loadpilot.backend.enums;

/** Notification a la fin d'une execution planifiee : a chaque fois, ou seulement en cas d'echec ou de regression. */
public enum ScheduleNotifyMode {
    ALWAYS,
    PROBLEMS
}
