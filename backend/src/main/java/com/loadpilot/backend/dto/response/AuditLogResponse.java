package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.service.audit.AuditChange;
import java.time.Instant;
import java.util.List;

/**
 * Une entree du journal d'audit, prete a afficher : libelles francais, phrase
 * lisible, objet concerne (et s'il existe encore), changements avant -> apres.
 * Jamais l'entite JPA exposee directement, jamais de secret.
 *
 * actorType : USER ou SYSTEM (actorSource = origine, ex. « Planification : Test nocturne »).
 * legacy : ligne anterieure au journal enrichi (description d'origine, sans objet ni avant / apres).
 */
public record AuditLogResponse(
        String id,
        Instant date,
        String actorType,
        String userId,
        String username,
        String actorSource,
        AuditAction action,
        String actionLabel,
        AuditModule module,
        String category,
        String categoryLabel,
        AuditResult result,
        String resultLabel,
        String summary,
        String detail,
        String targetType,
        String targetId,
        String targetLabel,
        boolean targetExists,
        List<AuditChange> changes,
        String ip,
        String ipLabel,
        String userAgent,
        boolean legacy,
        String description
) {
}
