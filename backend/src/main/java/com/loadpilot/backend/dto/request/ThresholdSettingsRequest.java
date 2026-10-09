package com.loadpilot.backend.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Seuils par defaut (Parametres > Systeme) : seuils proposes pour les nouveaux scenarios
 * (taux d'erreurs maximal, P95 maximal, 0 = aucun) et seuil de regression utilise partout.
 */
public record ThresholdSettingsRequest(
        @NotNull @DecimalMin(value = "0.0", message = "Taux d'erreurs maximal : entre 0 et 100 %.")
        @DecimalMax(value = "100.0", message = "Taux d'erreurs maximal : entre 0 et 100 %.") Double maxErrorRatePercent,
        @NotNull @Min(value = 0, message = "P95 maximal : 0 (aucun) ou une durée positive.")
        @Max(value = 600000, message = "P95 maximal : au plus 600 000 ms (10 min).") Long maxP95Ms,
        @NotNull @DecimalMin(value = "1.0", message = "Seuil de régression : entre 1 et 500 %.")
        @DecimalMax(value = "500.0", message = "Seuil de régression : entre 1 et 500 %.") Double regressionPercent
) {
}
