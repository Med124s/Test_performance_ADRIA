package com.loadpilot.backend.dto.request;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

/**
 * "Tester les etapes selectionnees" (passage produit reel, 2026-09-30) —
 * toutes les etapes DOIVENT appartenir au MEME Scenario (verifie dans
 * StepServiceImpl.testBatch, jamais suppose) : le test resout l'URL de base
 * via l'Application de CE scenario, comme une vraie Execution le ferait.
 */
public record StepTestBatchRequest(
        @NotEmpty(message = "Selectionnez au moins une etape a tester.")
        List<UUID> stepIds
) {
}
