package com.loadpilot.backend.dto.response;

/**
 * P1-D — vue en LECTURE SEULE des limites operationnelles reelles
 * actuellement actives sur ce backend (voir SystemConfigController). Toutes
 * les valeurs proviennent des memes proprietes Spring deja injectees
 * ailleurs (RunningExecutionRegistry, HttpClientExecutionEngine,
 * HttpAvailabilityChecker, ScheduledExecutionPoller) - jamais une valeur
 * dupliquee/recalculee, jamais un champ editable (aucune mutation
 * possible : ces limites restent volontairement pilotees par variable
 * d'environnement, voir application.yml et le rapport P1-D pour la
 * justification de ce choix).
 */
public record SystemConfigResponse(
        int maxVirtualUsersPerExecution,
        int maxGlobalVirtualUsers,
        int maxConcurrentExecutions,
        int executionTimeoutSeconds,
        int availabilityTimeoutSeconds,
        long schedulerPollIntervalMs
) {
}
