package com.loadpilot.backend.dto.response;

/** Seuils par defaut : maxP95Ms = 0 signifie « aucun seuil de P95 propose ». */
public record ThresholdSettingsResponse(double maxErrorRatePercent, long maxP95Ms, double regressionPercent) {
}
