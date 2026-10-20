package com.loadpilot.backend.service.validation;

/**
 * Limites de charge d'un test en vigueur (limites applicatives, jamais au-dessus des plafonds du
 * serveur, voir ExecutionLimitsService). maxVirtualUsers = limite reellement applicable a un test.
 * targetRpsAdvisedAboveVirtualUsers : au-dela de ce nombre d'utilisateurs virtuels, un debit cible est
 * fortement conseille (avertissement, voir LoadRules.warnings) ; 0 = jamais d'avertissement.
 */
public record LoadLimits(int maxVirtualUsers, int maxDurationSeconds, int maxIterations, int maxTargetRps,
                         int targetRpsAdvisedAboveVirtualUsers) {

    /** Seuil par defaut de l'avertissement « sans debit cible ». */
    public static final int DEFAULT_TARGET_RPS_ADVISED_ABOVE_VIRTUAL_USERS = 50;

    /** Compatibilite : seuil par defaut. */
    public LoadLimits(int maxVirtualUsers, int maxDurationSeconds, int maxIterations, int maxTargetRps) {
        this(maxVirtualUsers, maxDurationSeconds, maxIterations, maxTargetRps, DEFAULT_TARGET_RPS_ADVISED_ABOVE_VIRTUAL_USERS);
    }
}
