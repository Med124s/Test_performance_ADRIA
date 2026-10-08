package com.loadpilot.backend.enums;

/**
 * Etat d'une planification, calcule (jamais stocke) depuis ses champs et son scenario :
 * ACTIVE (se lancera), PAUSED (mise en pause par l'utilisateur), SUSPENDED (scenario inactif ou archive),
 * COMPLETED (planification unique deja lancee ou passee), EXPIRED (date de fin depassee).
 */
public enum ScheduleState {
    ACTIVE,
    PAUSED,
    SUSPENDED,
    COMPLETED,
    EXPIRED
}
