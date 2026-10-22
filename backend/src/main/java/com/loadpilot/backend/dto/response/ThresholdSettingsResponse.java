package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.RequestDetailMode;

/**
 * Valeurs par defaut : maxP95Ms = 0 signifie « aucun seuil de P95 propose ». Les reglages du frein,
 * du 429 et du detail des requetes sont ceux proposes aux NOUVEAUX scenarios (assistant et creation
 * par l'API sans ces champs) ; un scenario existant garde les siens.
 */
public record ThresholdSettingsResponse(
        double maxErrorRatePercent,
        long maxP95Ms,
        double regressionPercent,
        boolean errorBrakeEnabled,
        double errorBrakeRatePercent,
        int errorBrakeWindowSeconds,
        boolean throttleOn429,
        int throttleDefaultDelaySeconds,
        int throttleMaxRetries,
        RequestDetailMode requestDetailMode
) {
}
