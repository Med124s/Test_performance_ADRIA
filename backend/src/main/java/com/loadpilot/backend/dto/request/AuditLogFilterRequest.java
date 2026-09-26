package com.loadpilot.backend.dto.request;

import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import java.time.Instant;

/**
 * Filtre de RECHERCHE en lecture seule pour GET /api/audit-logs et
 * /api/audit-logs/export - construit par le controller a partir de query
 * params, jamais depuis un corps JSON envoye par un client pour CREER un
 * audit (voir Phase 12, section 8) : userId/date/ip/result d'une entree
 * d'audit ne sont JAMAIS fournis par un client pour fabriquer une entree -
 * seul AuditLogService.record(...), appele exclusivement depuis le code
 * backend (voir AuditLogServiceImpl), cree des AuditLog.
 */
public record AuditLogFilterRequest(
        String userId,
        String username,
        AuditAction action,
        AuditModule module,
        AuditResult result,
        Instant from,
        Instant to
) {
}
