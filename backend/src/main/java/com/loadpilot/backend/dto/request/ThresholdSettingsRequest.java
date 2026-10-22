package com.loadpilot.backend.dto.request;

import com.loadpilot.backend.enums.RequestDetailMode;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Valeurs par defaut (Parametres > Systeme) : seuils proposes pour les nouveaux scenarios (taux
 * d'erreurs maximal, P95 maximal, 0 = aucun), seuil de regression utilise partout, et reglages
 * proposes aux nouveaux scenarios (frein automatique, ralentissement sur 429, detail des requetes).
 *
 * Reglages des nouveaux scenarios : null = inchange (client qui ne les connait pas). Memes bornes
 * que ScenarioRequest, pour qu'une valeur proposee soit toujours enregistrable telle quelle.
 */
public record ThresholdSettingsRequest(
        @NotNull @DecimalMin(value = "0.0", message = "Taux d'erreurs maximal : entre 0 et 100 %.")
        @DecimalMax(value = "100.0", message = "Taux d'erreurs maximal : entre 0 et 100 %.") Double maxErrorRatePercent,
        @NotNull @Min(value = 0, message = "P95 maximal : 0 (aucun) ou une durée positive.")
        @Max(value = 600000, message = "P95 maximal : au plus 600 000 ms (10 min).") Long maxP95Ms,
        @NotNull @DecimalMin(value = "1.0", message = "Seuil de régression : entre 1 et 500 %.")
        @DecimalMax(value = "500.0", message = "Seuil de régression : entre 1 et 500 %.") Double regressionPercent,

        Boolean errorBrakeEnabled,
        @DecimalMin(value = "0.0", inclusive = false, message = "Taux du frein automatique : supérieur à 0 et au plus 100 %.")
        @DecimalMax(value = "100.0", message = "Taux du frein automatique : supérieur à 0 et au plus 100 %.") Double errorBrakeRatePercent,
        @Min(value = 5, message = "Fenêtre du frein automatique : entre 5 et 300 secondes.")
        @Max(value = 300, message = "Fenêtre du frein automatique : entre 5 et 300 secondes.") Integer errorBrakeWindowSeconds,
        Boolean throttleOn429,
        @Min(value = 1, message = "Attente après un 429 sans Retry-After : entre 1 et 300 secondes.")
        @Max(value = 300, message = "Attente après un 429 sans Retry-After : entre 1 et 300 secondes.") Integer throttleDefaultDelaySeconds,
        @Min(value = 0, message = "Rejeux après un 429 : entre 0 et 10.")
        @Max(value = 10, message = "Rejeux après un 429 : entre 0 et 10.") Integer throttleMaxRetries,
        RequestDetailMode requestDetailMode
) {
    /** Compatibilite : seuils seulement, reglages des nouveaux scenarios inchanges. */
    public ThresholdSettingsRequest(Double maxErrorRatePercent, Long maxP95Ms, Double regressionPercent) {
        this(maxErrorRatePercent, maxP95Ms, regressionPercent, null, null, null, null, null, null, null);
    }
}
