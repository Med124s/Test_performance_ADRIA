package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import java.time.Instant;

/** Jamais l'entite JPA AuditLog exposee directement. */
public record AuditLogResponse(
        String id,
        String userId,
        String username,
        AuditAction action,
        AuditModule module,
        Instant date,
        String ip,
        AuditResult result,
        String description
) {
}
