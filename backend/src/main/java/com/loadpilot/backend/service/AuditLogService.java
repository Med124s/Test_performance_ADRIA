package com.loadpilot.backend.service;

import com.loadpilot.backend.dto.request.AuditLogFilterRequest;
import com.loadpilot.backend.dto.response.AuditLogResponse;
import com.loadpilot.backend.dto.response.AuditStatsResponse;
import com.loadpilot.backend.dto.response.PagedResponse;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.entity.AppUser;
import java.util.UUID;

public interface AuditLogService {

    /**
     * Enregistre reellement un AuditLog (userId/username/ip/date resolus en
     * interne depuis le contexte de la requete courante - voir
     * AuditLogServiceImpl). Ne leve JAMAIS d'exception : un echec
     * d'ecriture de l'audit ne doit jamais faire echouer l'operation
     * metier appelante (voir Phase 12, section 15).
     */
    void record(AuditAction action, AuditModule module, AuditResult result, String description);

    /**
     * P1-B — variante pour un acteur CONNU explicitement (jamais resolu
     * depuis le SecurityContext) : necessaire pour ScheduledExecutionPoller,
     * qui declenche des Executions en dehors de tout thread de requete HTTP
     * (donc sans aucun SecurityContext/Jwt disponible) - l'acteur reel est
     * alors le proprietaire de la ScheduledExecution (AppUser deja charge),
     * jamais un acteur invente ni un simple null silencieux. "ip" est
     * toujours null ici (aucune requete HTTP associee).
     */
    void record(AppUser actor, AuditAction action, AuditModule module, AuditResult result, String description);

    /** @throws com.loadpilot.backend.exception.ResourceNotFoundException si l'AuditLog n'existe pas. */
    AuditLogResponse getById(UUID id);

    PagedResponse<AuditLogResponse> search(AuditLogFilterRequest filter, int page, int size);

    AuditStatsResponse getStats();

    /** Contenu CSV complet (avec en-tete) des AuditLog correspondant a {@code filter}. */
    String exportCsv(AuditLogFilterRequest filter);
}
