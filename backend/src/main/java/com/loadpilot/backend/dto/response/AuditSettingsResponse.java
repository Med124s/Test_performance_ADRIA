package com.loadpilot.backend.dto.response;

/** Reglages du journal d'audit : consultations journalisees ou non, duree de conservation (jours). */
public record AuditSettingsResponse(boolean logReads, int retentionDays) {
}
