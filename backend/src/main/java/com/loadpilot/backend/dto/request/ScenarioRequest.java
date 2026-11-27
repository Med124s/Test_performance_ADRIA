package com.loadpilot.backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.loadpilot.backend.enums.CsvDistribution;
import java.util.UUID;

/** Creation (POST) et mise a jour (PUT) d'un scenario ; champs de charge optionnels. */
public record ScenarioRequest(

        @NotNull(message = "L'identifiant de l'application est obligatoire.")
        UUID applicationId,

        @NotBlank(message = "Le nom du scenario est obligatoire.")
        @Size(max = 255, message = "Le nom ne doit pas depasser 255 caracteres.")
        String name,

        @Size(max = 1000, message = "La description ne doit pas depasser 1000 caracteres.")
        String description,

        @Min(value = 1, message = "Le nombre d’utilisateurs doit être au moins 1.")
        @Max(value = 10000, message = "Le nombre d’utilisateurs ne doit pas dépasser 10000.")
        Integer virtualUsers,

        @Min(value = 0, message = "Le ramp-up doit être au moins 0.")
        Integer rampUpSeconds,

        @Min(value = 1, message = "La durée doit être au moins 1.")
        Integer durationSeconds,

        @Min(value = 1, message = "Le nombre d’itérations doit être au moins 1.")
        Integer iterations,

        @Min(value = 0, message = "Le think time doit être au moins 0.")
        Integer thinkTimeMs,

        /** Donnees CSV optionnelles ; la premiere ligne donne les noms des variables. */
        @Size(max = 1_000_000, message = "Les données CSV ne doivent pas dépasser 1 000 000 caractères.")
        String csvData,

        /** Debit cible optionnel (requetes/s) ; null = aucun pacing. */
        @Min(value = 1, message = "Le débit cible doit être au moins 1.")
        Integer targetRps,

        // Creation uniquement : true cree un brouillon (DRAFT) au lieu d'un scenario ACTIVE.
        // Un brouillon ne devient ACTIVE que via POST /api/scenarios/{id}/publish.
        Boolean draft,

        // Brouillon uniquement : etape de l'assistant atteinte (null = inchangee).
        @Min(value = 0, message = "L'étape de l'assistant doit être comprise entre 0 et 3.")
        @Max(value = 3, message = "L'étape de l'assistant doit être comprise entre 0 et 3.")
        Integer wizardStep,

        // Brouillon uniquement : etat brut de l'assistant en JSON (null = inchange).
        @Size(max = 1_000_000, message = "Le brouillon est trop volumineux (1 000 000 caractères maximum).")
        String draftState,

        // Variables du scenario : objet JSON {"nom": "valeur"} (noms : lettres, chiffres, _).
        // null = aucune ; validees par ScenarioVariables.validate.
        @Size(max = 20000, message = "Les variables du scénario ne doivent pas dépasser 20000 caractères.")
        String variables,

        // Distribution du CSV : PER_VIRTUAL_USER (defaut) ou PER_ITERATION.
        CsvDistribution csvDistribution,

        // Detail des requetes conserve a l'execution : NONE, FAILURES ou FAILURES_AND_SAMPLE (defaut).
        com.loadpilot.backend.enums.RequestDetailMode requestDetailMode,

        /** Seuil de reussite : taux d'erreurs maximal (%) ; null = inchange (defaut 1 % a la creation). */
        @jakarta.validation.constraints.DecimalMin(value = "0.0", message = "Le taux d'erreurs maximal ne peut pas être négatif.")
        @jakarta.validation.constraints.DecimalMax(value = "100.0", message = "Le taux d'erreurs maximal ne peut pas dépasser 100 %.")
        Double maxErrorRatePercent,

        /** Seuil de reussite optionnel : P95 maximal (ms) ; null = inchange, 0 = aucun seuil de P95. */
        @Min(value = 0, message = "Le P95 maximal ne peut pas être négatif.")
        Long maxP95Ms,

        /** Frein automatique : taux d'erreurs (%) qui arrete le test en direct ; null = inchange, 0 = desactive. */
        @jakarta.validation.constraints.DecimalMin(value = "0.0", message = "Le taux d'erreurs du frein automatique ne peut pas être négatif.")
        @jakarta.validation.constraints.DecimalMax(value = "100.0", message = "Le taux d'erreurs du frein automatique ne peut pas dépasser 100 %.")
        Double errorBrakeRatePercent,

        /** Fenetre glissante du frein automatique (secondes) ; null = inchange (30 s par defaut). */
        @Min(value = 5, message = "La fenêtre du frein automatique doit être d'au moins 5 secondes.")
        @jakarta.validation.constraints.Max(value = 300, message = "La fenêtre du frein automatique ne doit pas dépasser 300 secondes.")
        Integer errorBrakeWindowSeconds,

        /** Ralentissement sur 429 Too Many Requests ; null = inchange (actif par defaut), false = desactive. */
        Boolean throttleOn429,

        /** Attente apres un 429 sans en-tete Retry-After (secondes) ; null = inchange (5 s par defaut). */
        @Min(value = 1, message = "L'attente par défaut après un 429 doit être d'au moins 1 seconde.")
        @Max(value = 300, message = "L'attente par défaut après un 429 ne doit pas dépasser 300 secondes.")
        Integer throttleDefaultDelaySeconds,

        /** Rejeux au plus d'une etape apres un 429 ; null = inchange (3 par defaut). */
        @Min(value = 0, message = "Le nombre de rejeux après un 429 ne peut pas être négatif.")
        @Max(value = 10, message = "Le nombre de rejeux après un 429 ne doit pas dépasser 10.")
        Integer throttleMaxRetries,

        /** Variation aleatoire du think time (± %, uniforme) ; null = inchange, 0 = think time fixe (defaut). */
        @Min(value = 0, message = "La variation du think time ne peut pas être négative.")
        @Max(value = 100, message = "La variation du think time ne doit pas dépasser 100 %.")
        Integer thinkTimeVariationPercent,

        /** Mode de charge : MANUAL ou STRESS (reglages de charge ignores) ; null = inchange (MANUAL a la creation). */
        com.loadpilot.backend.enums.LoadMode loadMode,

        /** Mode stress : vitesse de montee (req/s gagnees chaque seconde) ; null = inchangee (1 par defaut). */
        @Min(value = com.loadpilot.backend.service.execution.StressSettings.MIN_RAMP_RPS_PER_SECOND,
                message = "La vitesse de montée doit être d'au moins 1 req/s par seconde.")
        @Max(value = com.loadpilot.backend.service.execution.StressSettings.MAX_RAMP_RPS_PER_SECOND,
                message = "La vitesse de montée ne doit pas dépasser 50 req/s par seconde.")
        Integer stressRampStep
) {
    public ScenarioRequest(UUID applicationId, String name, String description, Integer virtualUsers,
            Integer rampUpSeconds, Integer durationSeconds, Integer iterations, Integer thinkTimeMs,
            String csvData, Integer targetRps, Boolean draft, Integer wizardStep, String draftState,
            String variables, CsvDistribution csvDistribution, com.loadpilot.backend.enums.RequestDetailMode requestDetailMode,
            Double maxErrorRatePercent, Long maxP95Ms, Double errorBrakeRatePercent, Integer errorBrakeWindowSeconds,
            Boolean throttleOn429, Integer throttleDefaultDelaySeconds, Integer throttleMaxRetries, Integer thinkTimeVariationPercent,
            com.loadpilot.backend.enums.LoadMode loadMode) {
        this(applicationId, name, description, virtualUsers, rampUpSeconds, durationSeconds, iterations, thinkTimeMs, csvData,
                targetRps, draft, wizardStep, draftState, variables, csvDistribution, requestDetailMode, maxErrorRatePercent, maxP95Ms,
                errorBrakeRatePercent, errorBrakeWindowSeconds, throttleOn429, throttleDefaultDelaySeconds, throttleMaxRetries,
                thinkTimeVariationPercent, loadMode, null);
    }

    public ScenarioRequest(UUID applicationId, String name, String description, Integer virtualUsers,
            Integer rampUpSeconds, Integer durationSeconds, Integer iterations, Integer thinkTimeMs,
            String csvData, Integer targetRps, Boolean draft, Integer wizardStep, String draftState,
            String variables, CsvDistribution csvDistribution, com.loadpilot.backend.enums.RequestDetailMode requestDetailMode,
            Double maxErrorRatePercent, Long maxP95Ms, Double errorBrakeRatePercent, Integer errorBrakeWindowSeconds,
            Boolean throttleOn429, Integer throttleDefaultDelaySeconds, Integer throttleMaxRetries, Integer thinkTimeVariationPercent) {
        this(applicationId, name, description, virtualUsers, rampUpSeconds, durationSeconds, iterations, thinkTimeMs, csvData,
                targetRps, draft, wizardStep, draftState, variables, csvDistribution, requestDetailMode, maxErrorRatePercent, maxP95Ms,
                errorBrakeRatePercent, errorBrakeWindowSeconds, throttleOn429, throttleDefaultDelaySeconds, throttleMaxRetries,
                thinkTimeVariationPercent, null);
    }

    public ScenarioRequest(UUID applicationId, String name, String description, Integer virtualUsers,
            Integer rampUpSeconds, Integer durationSeconds, Integer iterations, Integer thinkTimeMs,
            String csvData, Integer targetRps, Boolean draft, Integer wizardStep, String draftState,
            String variables, CsvDistribution csvDistribution, com.loadpilot.backend.enums.RequestDetailMode requestDetailMode,
            Double maxErrorRatePercent, Long maxP95Ms, Double errorBrakeRatePercent, Integer errorBrakeWindowSeconds,
            Boolean throttleOn429, Integer throttleDefaultDelaySeconds, Integer throttleMaxRetries) {
        this(applicationId, name, description, virtualUsers, rampUpSeconds, durationSeconds, iterations, thinkTimeMs, csvData,
                targetRps, draft, wizardStep, draftState, variables, csvDistribution, requestDetailMode, maxErrorRatePercent, maxP95Ms,
                errorBrakeRatePercent, errorBrakeWindowSeconds, throttleOn429, throttleDefaultDelaySeconds, throttleMaxRetries, null);
    }

    public ScenarioRequest(UUID applicationId, String name, String description, Integer virtualUsers,
            Integer rampUpSeconds, Integer durationSeconds, Integer iterations, Integer thinkTimeMs,
            String csvData, Integer targetRps, Boolean draft, Integer wizardStep, String draftState,
            String variables, CsvDistribution csvDistribution, com.loadpilot.backend.enums.RequestDetailMode requestDetailMode,
            Double maxErrorRatePercent, Long maxP95Ms, Double errorBrakeRatePercent, Integer errorBrakeWindowSeconds) {
        this(applicationId, name, description, virtualUsers, rampUpSeconds, durationSeconds, iterations, thinkTimeMs, csvData,
                targetRps, draft, wizardStep, draftState, variables, csvDistribution, requestDetailMode, maxErrorRatePercent, maxP95Ms,
                errorBrakeRatePercent, errorBrakeWindowSeconds, null, null, null, null);
    }

    public ScenarioRequest(UUID applicationId, String name, String description, Integer virtualUsers,
            Integer rampUpSeconds, Integer durationSeconds, Integer iterations, Integer thinkTimeMs,
            String csvData, Integer targetRps, Boolean draft, Integer wizardStep, String draftState,
            String variables, CsvDistribution csvDistribution, com.loadpilot.backend.enums.RequestDetailMode requestDetailMode,
            Double maxErrorRatePercent, Long maxP95Ms) {
        this(applicationId, name, description, virtualUsers, rampUpSeconds, durationSeconds, iterations, thinkTimeMs, csvData,
                targetRps, draft, wizardStep, draftState, variables, csvDistribution, requestDetailMode, maxErrorRatePercent, maxP95Ms,
                null, null);
    }

    public ScenarioRequest(UUID applicationId, String name, String description, Integer virtualUsers,
            Integer rampUpSeconds, Integer durationSeconds, Integer iterations, Integer thinkTimeMs,
            String csvData, Integer targetRps, Boolean draft, Integer wizardStep, String draftState,
            String variables, CsvDistribution csvDistribution, com.loadpilot.backend.enums.RequestDetailMode requestDetailMode) {
        this(applicationId, name, description, virtualUsers, rampUpSeconds, durationSeconds, iterations,
                thinkTimeMs, csvData, targetRps, draft, wizardStep, draftState, variables, csvDistribution, requestDetailMode, null, null);
    }

    public ScenarioRequest(UUID applicationId, String name, String description, Integer virtualUsers,
            Integer rampUpSeconds, Integer durationSeconds, Integer iterations, Integer thinkTimeMs,
            String csvData, Integer targetRps, Boolean draft, Integer wizardStep, String draftState,
            String variables, CsvDistribution csvDistribution) {
        this(applicationId, name, description, virtualUsers, rampUpSeconds, durationSeconds, iterations,
                thinkTimeMs, csvData, targetRps, draft, wizardStep, draftState, variables, csvDistribution, null, null, null);
    }

    public ScenarioRequest(UUID applicationId, String name, String description) {
        this(applicationId, name, description, null, null, null, null, null, null, null);
    }

    public ScenarioRequest(UUID applicationId, String name, String description, Integer virtualUsers,
            Integer rampUpSeconds, Integer durationSeconds, Integer iterations, Integer thinkTimeMs,
            String csvData, Integer targetRps) {
        this(applicationId, name, description, virtualUsers, rampUpSeconds, durationSeconds, iterations,
                thinkTimeMs, csvData, targetRps, null, null, null);
    }

    public ScenarioRequest(UUID applicationId, String name, String description, Integer virtualUsers,
            Integer rampUpSeconds, Integer durationSeconds, Integer iterations, Integer thinkTimeMs,
            String csvData, Integer targetRps, Boolean draft, Integer wizardStep, String draftState) {
        this(applicationId, name, description, virtualUsers, rampUpSeconds, durationSeconds, iterations,
                thinkTimeMs, csvData, targetRps, draft, wizardStep, draftState, null, null);
    }
}
