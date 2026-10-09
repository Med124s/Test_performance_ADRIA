package com.loadpilot.backend.service.validation;

/**
 * Limites de charge d'un test en vigueur (limites applicatives, jamais au-dessus des plafonds du
 * serveur, voir ExecutionLimitsService). maxVirtualUsers = limite reellement applicable a un test.
 */
public record LoadLimits(int maxVirtualUsers, int maxDurationSeconds, int maxIterations, int maxTargetRps) {
}
