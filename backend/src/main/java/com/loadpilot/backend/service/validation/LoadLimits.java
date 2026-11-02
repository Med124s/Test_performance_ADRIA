package com.loadpilot.backend.service.validation;

/** Limites de charge d'un test en vigueur. */
public record LoadLimits(int maxVirtualUsers, int maxDurationSeconds, int maxIterations, int maxTargetRps,
                         int targetRpsAdvisedAboveVirtualUsers) {

    /** Seuil par defaut de l'avertissement « sans debit cible ». */
    public static final int DEFAULT_TARGET_RPS_ADVISED_ABOVE_VIRTUAL_USERS = 50;

    public LoadLimits(int maxVirtualUsers, int maxDurationSeconds, int maxIterations, int maxTargetRps) {
        this(maxVirtualUsers, maxDurationSeconds, maxIterations, maxTargetRps, DEFAULT_TARGET_RPS_ADVISED_ABOVE_VIRTUAL_USERS);
    }
}
