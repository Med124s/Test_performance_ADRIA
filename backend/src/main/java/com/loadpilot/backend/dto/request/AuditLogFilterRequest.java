package com.loadpilot.backend.dto.request;

import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.service.audit.AuditCategory;
import java.time.Instant;

/** Filtres de recherche du journal d'audit (parametres de requete). */
public record AuditLogFilterRequest(
        String q,
        String actor,
        AuditCategory category,
        String userId,
        String username,
        AuditAction action,
        AuditModule module,
        AuditResult result,
        Instant from,
        Instant to
) {

    public static final String SYSTEM_ACTOR = "__system";

    public AuditLogFilterRequest(String userId, String username, AuditAction action, AuditModule module,
                                 AuditResult result, Instant from, Instant to) {
        this(null, null, null, userId, username, action, module, result, from, to);
    }
}
