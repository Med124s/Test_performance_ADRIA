package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.service.validation.LoadLimits;
import com.loadpilot.backend.service.validation.LoadRules;
import java.util.List;

/**
 * Charge par defaut et limites en vigueur (lues par l'assistant pour valider et afficher « 1 a X »).
 * problems : regles que la charge par defaut enregistree ne respecte plus (une limite a baisse) ;
 * vide si tout va bien.
 */
public record LoadDefaultsResponse(
        int virtualUsers,
        int thinkTimeMs,
        int rampUpSeconds,
        Integer durationSeconds,
        Integer iterations,
        Integer targetRps,
        int maxVirtualUsers,
        int maxDurationSeconds,
        int maxIterations,
        int maxTargetRps,
        List<String> problems
) {
    public LoadDefaultsResponse withLimits(LoadLimits limits) {
        return new LoadDefaultsResponse(virtualUsers, thinkTimeMs, rampUpSeconds, durationSeconds, iterations, targetRps,
                limits.maxVirtualUsers(), limits.maxDurationSeconds(), limits.maxIterations(), limits.maxTargetRps(),
                LoadRules.validate(virtualUsers, thinkTimeMs, rampUpSeconds, durationSeconds, iterations, targetRps, limits));
    }
}
