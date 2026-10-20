package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.ScenarioStatus;
import java.time.Instant;

/**
 * "applicationName" est expose en plus de "applicationId" par confort
 * d'affichage - jamais l'entite Application complete. "createdBy" est le nom
 * d'affichage du createur, jamais l'entite AppUser.
 */
public record ScenarioResponse(
        String id,
        String applicationId,
        String applicationName,
        String name,
        String description,
        ScenarioStatus status,
        Integer virtualUsers,
        Integer rampUpSeconds,
        Integer durationSeconds,
        Integer iterations,
        Integer thinkTimeMs,
        String csvData,
        Integer targetRps,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        boolean archived,
        Instant archivedAt,
        /** Vrai si l'application du scenario est archivee (scenario masque). */
        boolean applicationArchived,
        /** Brouillons uniquement : etape de l'assistant atteinte (0..3). */
        Integer wizardStep,
        /** Brouillons uniquement : etat brut de l'assistant (JSON). */
        String draftState,
        /** Planifications suspendues par la desactivation du scenario (reactivables). */
        long pausedSchedules,
        /** Variables du scenario (objet JSON) et distribution du CSV. */
        String variables,
        com.loadpilot.backend.enums.CsvDistribution csvDistribution,
        /** Detail des requetes conserve a l'execution (aucun / echecs / echecs + echantillon). */
        com.loadpilot.backend.enums.RequestDetailMode requestDetailMode,
        /** Seuils de reussite : taux d'erreurs maximal (%) et P95 maximal (ms, null = aucun). */
        Double maxErrorRatePercent,
        Long maxP95Ms,
        /** Frein automatique en direct : taux d'erreurs (%, null = desactive) et fenetre (secondes). */
        Double errorBrakeRatePercent,
        Integer errorBrakeWindowSeconds,
        /** Ralentissement sur 429 : actif (null = actif), attente sans Retry-After (s, null = 5), rejeux (null = 3). */
        Boolean throttleOn429,
        Integer throttleDefaultDelaySeconds,
        Integer throttleMaxRetries,
        /** Variation aleatoire du think time (± %) ; null = think time fixe. */
        Integer thinkTimeVariationPercent,
        /** Avertissements de charge, jamais bloquants (ex. beaucoup d'utilisateurs sans debit cible, voir LoadRules.warnings). */
        java.util.List<String> loadWarnings
) {
    public ScenarioResponse withPausedSchedules(long count) {
        return new ScenarioResponse(id, applicationId, applicationName, name, description, status, virtualUsers,
                rampUpSeconds, durationSeconds, iterations, thinkTimeMs, csvData, targetRps, createdBy, createdAt,
                updatedAt, archived, archivedAt, applicationArchived, wizardStep, draftState, count, variables, csvDistribution, requestDetailMode, maxErrorRatePercent, maxP95Ms,
                errorBrakeRatePercent, errorBrakeWindowSeconds, throttleOn429, throttleDefaultDelaySeconds, throttleMaxRetries, thinkTimeVariationPercent, loadWarnings);
    }

    public ScenarioResponse withLoadWarnings(java.util.List<String> warnings) {
        return new ScenarioResponse(id, applicationId, applicationName, name, description, status, virtualUsers,
                rampUpSeconds, durationSeconds, iterations, thinkTimeMs, csvData, targetRps, createdBy, createdAt,
                updatedAt, archived, archivedAt, applicationArchived, wizardStep, draftState, pausedSchedules, variables, csvDistribution, requestDetailMode, maxErrorRatePercent, maxP95Ms,
                errorBrakeRatePercent, errorBrakeWindowSeconds, throttleOn429, throttleDefaultDelaySeconds, throttleMaxRetries, thinkTimeVariationPercent, warnings);
    }
}
