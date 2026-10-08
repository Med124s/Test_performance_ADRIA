package com.loadpilot.backend.service;

import com.loadpilot.backend.dto.request.AuditLogFilterRequest;
import com.loadpilot.backend.dto.response.AuditLogResponse;
import com.loadpilot.backend.dto.response.AuditStatsResponse;
import com.loadpilot.backend.dto.response.PagedResponse;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.service.audit.AuditEvent;
import java.util.List;
import java.util.UUID;

public interface AuditLogService {

    /**
     * Enregistre reellement une entree (acteur, IP, navigateur et date resolus
     * en interne depuis la requete courante, sauf action automatique ou
     * evenement importe). Ne leve JAMAIS d'exception : un echec d'ecriture de
     * l'audit ne doit jamais faire echouer l'operation metier appelante (voir
     * Phase 12, section 15). Ignore :
     * - une consultation si le reglage « Journaliser aussi les consultations » est desactive ;
     * - une action strictement identique a la precedente dans la meme seconde.
     */
    void record(AuditEvent event);

    /** Forme courte historique (sans objet ni avant / apres). */
    default void record(AuditAction action, AuditModule module, AuditResult result, String description) {
        record(AuditEvent.of(action, module).result(result).description(description));
    }

    /** @throws com.loadpilot.backend.exception.ResourceNotFoundException si l'AuditLog n'existe pas. */
    AuditLogResponse getById(UUID id);

    PagedResponse<AuditLogResponse> search(AuditLogFilterRequest filter, int page, int size);

    AuditStatsResponse getStats();

    /** Utilisateurs ayant au moins une action (liste du filtre « Utilisateur »). */
    List<String> actors();

    /** Export CSV en francais (separateur « ; », UTF-8 avec BOM pour Excel) respectant {@code filter}. */
    String exportCsv(AuditLogFilterRequest filter);
}
