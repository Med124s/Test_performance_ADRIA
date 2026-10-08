package com.loadpilot.backend.dto.request;

import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.service.audit.AuditCategory;
import java.time.Instant;

/**
 * Filtre de RECHERCHE en lecture seule pour GET /api/audit-logs et
 * /api/audit-logs/export - construit par le controller a partir de query
 * params, jamais depuis un corps JSON envoye par un client pour CREER un
 * audit (voir Phase 12, section 8) : seul AuditLogService.record(...),
 * appele exclusivement depuis le code backend, cree des AuditLog.
 *
 * q : recherche libre (utilisateur, objet, description) ;
 * actor : nom d'utilisateur, ou "__system" pour les actions automatiques ;
 * category : regroupement de modules (voir AuditCategory).
 * userId / username / action / module : filtres historiques de l'API, toujours acceptes.
 */
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

    /** Filtres historiques uniquement (compatibilite). */
    public AuditLogFilterRequest(String userId, String username, AuditAction action, AuditModule module,
                                 AuditResult result, Instant from, Instant to) {
        this(null, null, null, userId, username, action, module, result, from, to);
    }
}
