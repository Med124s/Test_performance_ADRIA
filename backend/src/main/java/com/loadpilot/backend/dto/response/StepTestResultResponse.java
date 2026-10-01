package com.loadpilot.backend.dto.response;

/**
 * Resultat REEL (jamais simule) du test d'UNE etape - voir
 * StepServiceImpl.testBatch/HttpClientExecutionEngine.testSteps.
 * "httpStatus" null = aucune reponse HTTP recue (erreur technique/timeout,
 * voir "error"). "assertionPassed" null = aucune assertion configuree sur
 * cette etape (rien a evaluer, jamais un faux "passe").
 */
public record StepTestResultResponse(
        String stepId,
        String stepName,
        boolean success,
        Integer httpStatus,
        long responseTimeMs,
        String error,
        Boolean assertionPassed
) {
}
