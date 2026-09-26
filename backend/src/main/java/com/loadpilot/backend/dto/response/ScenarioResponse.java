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
        Instant updatedAt
) {
}
