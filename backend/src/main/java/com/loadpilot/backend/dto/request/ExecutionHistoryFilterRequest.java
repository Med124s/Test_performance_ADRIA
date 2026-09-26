package com.loadpilot.backend.dto.request;

import com.loadpilot.backend.enums.ExecutionStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * P1-A — filtre de RECHERCHE en lecture seule pour GET /api/executions/history
 * (meme politique que AuditLogFilterRequest : construit par le controller a
 * partir de query params, jamais depuis un corps JSON). Tous les champs
 * sont optionnels et combines en ET (voir ExecutionSpecifications).
 *
 * "search" : recherche textuelle sur le nom du Scenario, le nom de
 * l'Application, ou l'id (UUID) de l'Execution (voir section 6 du prompt
 * P1-A) - executee cote base (LIKE), jamais apres avoir charge la table en
 * memoire.
 */
public record ExecutionHistoryFilterRequest(
        ExecutionStatus status,
        UUID scenarioId,
        UUID applicationId,
        Instant dateFrom,
        Instant dateTo,
        String search
) {
}
