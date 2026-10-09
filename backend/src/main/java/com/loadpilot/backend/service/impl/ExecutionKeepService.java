package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.dto.response.ResultsRetentionResponse;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.exception.ConflictException;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.service.AuditLogService;
import com.loadpilot.backend.service.audit.AuditEvent;
import com.loadpilot.backend.service.audit.AuditTargetType;
import com.loadpilot.backend.service.audit.AuditTargets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Option « Conserver » d'une execution : ses resultats et le detail de ses requetes ne sont
 * jamais purges. Impossible une fois le detail supprime (seul le rapport fige reste).
 * Ligne verrouillee : jamais en meme temps qu'une purge de cette execution.
 */
@Service
@RequiredArgsConstructor
public class ExecutionKeepService {

    static final String ALREADY_PURGED = "Le détail des requêtes de cette exécution a déjà été supprimé : seul le résumé est conservé.";

    private final ExecutionRepository executionRepository;
    private final ReportSnapshots reportSnapshots;
    private final AuditLogService auditLogService;

    @Transactional
    public ResultsRetentionResponse setKeep(UUID id, boolean keep) {
        Execution execution = executionRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Exécution introuvable : " + id));
        if (keep && execution.getResultsPurgedAt() != null) {
            throw new ConflictException(ALREADY_PURGED);
        }
        boolean before = execution.isKeepResults();
        if (before != keep) {
            execution.setKeepResults(keep);
            auditLogService.record(AuditEvent.of(AuditAction.UPDATE, AuditModule.EXECUTION)
                    .target(AuditTargetType.EXECUTION, id, AuditTargets.executionLabel(execution))
                    .change("Conservation des résultats", label(before), label(keep))
                    .description(keep ? "Exécution conservée : ses résultats détaillés ne seront jamais purgés"
                            : "Conservation retirée : les résultats détaillés suivront la durée de conservation"));
        }
        return reportSnapshots.retention(execution, false, false);
    }

    private static String label(boolean keep) {
        return keep ? "Conservés (jamais purgés)" : "Durée de conservation";
    }
}
