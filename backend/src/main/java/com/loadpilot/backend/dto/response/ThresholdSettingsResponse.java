package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.RequestDetailMode;

/** Valeurs proposees aux nouveaux scenarios ; maxP95Ms = 0 : aucun seuil de P95. */
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
