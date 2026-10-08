package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.service.audit.AuditEvent;
import com.loadpilot.backend.service.audit.AuditTargetType;
import com.loadpilot.backend.service.audit.AuditTargets;
import com.loadpilot.backend.dto.response.BulkActionResponse;
import com.loadpilot.backend.dto.response.ExecutionDeletionImpactResponse;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.exception.ConflictException;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.service.AuditLogService;
import com.loadpilot.backend.service.execution.RunningExecutionRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Suppression DEFINITIVE d'executions (simple ou groupee) : resultats des requetes, metriques
 * puis execution, dans une transaction par execution. Les liens qui ne sont que des identifiants
 * (notification, derniere execution d'une planification) sont remis a null.
 *
 * Jamais pour une execution en attente, en cours ou en pause, ni pour une execution qui termine
 * son enregistrement (resultats / metriques en cours d'ecriture juste apres la fin) : 409 clair.
 * En groupe, les autres sont supprimees quand meme (resultat detaille par execution).
 */
@Service
@RequiredArgsConstructor
public class ExecutionDeletionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionDeletionService.class);

    public static final String ACTIVE_MESSAGE =
            "Exécution %s : annulez-la (ou attendez sa fin) avant de la supprimer.";
    public static final String FINALIZING_MESSAGE =
            "L'exécution termine l'enregistrement de ses résultats : réessayez dans quelques secondes.";

    private final ExecutionRepository executionRepository;
    private final RunningExecutionRegistry registry;
    private final AuditLogService auditLogService;
    private final AuditTargets auditTargets;
    private final TransactionTemplate transactionTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    /** Impact cumule (executions, resultats, metriques, executions actives ignorees). */
    public ExecutionDeletionImpactResponse impact(List<UUID> executionIds) {
        List<UUID> ids = executionIds.stream().distinct().toList();
        return transactionTemplate.execute(status -> {
            long existing = count("SELECT COUNT(e) FROM Execution e WHERE e.id IN :ids", ids);
            if (existing == 0) {
                throw new ResourceNotFoundException("Exécution introuvable (déjà supprimée ?).");
            }
            long active = count("SELECT COUNT(e) FROM Execution e WHERE e.id IN :ids AND e.status IN ("
                    + "com.loadpilot.backend.enums.ExecutionStatus.QUEUED, com.loadpilot.backend.enums.ExecutionStatus.RUNNING, "
                    + "com.loadpilot.backend.enums.ExecutionStatus.PAUSED)", ids);
            String deletable = "e.id IN :ids AND e.status NOT IN (com.loadpilot.backend.enums.ExecutionStatus.QUEUED, "
                    + "com.loadpilot.backend.enums.ExecutionStatus.RUNNING, com.loadpilot.backend.enums.ExecutionStatus.PAUSED)";
            return new ExecutionDeletionImpactResponse(
                    existing,
                    count("SELECT COUNT(r) FROM ExecutionStepResult r JOIN r.execution e WHERE " + deletable, ids),
                    count("SELECT COUNT(m) FROM Metric m JOIN m.execution e WHERE " + deletable, ids),
                    active);
        });
    }

    /** Supprime une execution terminee ; 404 si introuvable, 409 si active. Auditee. */
    public void delete(UUID id) {
        String[] described = null;
        try {
            described = transactionTemplate.execute(status -> deleteInTransaction(id));
            auditLogService.record(AuditEvent.of(AuditAction.DELETE, AuditModule.EXECUTION)
                    .target(AuditTargetType.EXECUTION, id, described[0])
                    .description("Exécution supprimée avec ses résultats (statut : " + described[1] + ")"));
        } catch (RuntimeException e) {
            auditLogService.record(auditTargets.onExecution(AuditEvent.of(AuditAction.DELETE, AuditModule.EXECUTION), id)
                    .description("Suppression impossible").failed(e));
            throw e;
        }
    }

    /** Suppression groupee : chaque execution dans sa propre transaction, resultat par execution. */
    public BulkActionResponse deleteMany(List<UUID> ids) {
        List<BulkActionResponse.Item> results = new ArrayList<>();
        for (UUID id : ids.stream().distinct().toList()) {
            String name = executionRepository.findByIdWithScenarioAndApplication(id).map(AuditTargets::executionLabel).orElse(null);
            try {
                delete(id);
                results.add(new BulkActionResponse.Item(id.toString(), name, true, "Supprimée."));
            } catch (ConflictException e) {
                results.add(new BulkActionResponse.Item(id.toString(), name, false, e.getMessage()));
            } catch (ResourceNotFoundException e) {
                results.add(new BulkActionResponse.Item(id.toString(), name, false, "Exécution introuvable (déjà supprimée ?)."));
            } catch (RuntimeException e) {
                log.warn("Suppression groupee echouee pour l'execution {} : {}", id, e.getClass().getSimpleName());
                results.add(new BulkActionResponse.Item(id.toString(), name, false, "Erreur inattendue, réessayez."));
            }
        }
        return BulkActionResponse.of(results);
    }

    private String[] deleteInTransaction(UUID id) {
        Execution execution = executionRepository.findByIdWithScenarioAndApplicationForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Exécution introuvable (déjà supprimée ?)."));
        if (execution.getStatus().isActive()) {
            throw new ConflictException(String.format(ACTIVE_MESSAGE, switch (execution.getStatus()) {
                case QUEUED -> "en attente";
                case PAUSED -> "en pause";
                default -> "en cours";
            }));
        }
        // Statut deja terminal mais tache asynchrone pas encore finie (metriques en cours d'ecriture).
        if (registry.get(id) != null) {
            throw new ConflictException(FINALIZING_MESSAGE);
        }
        String[] label = { AuditTargets.executionLabel(execution), AuditTargets.statusLabel(execution.getStatus()) };
        entityManager.flush();
        nativeUpdate("DELETE FROM metric WHERE execution_id = :id", id);
        nativeUpdate("DELETE FROM execution_step_result WHERE execution_id = :id", id);
        nativeUpdate("UPDATE notification SET related_execution_id = NULL WHERE related_execution_id = :id", id);
        nativeUpdate("UPDATE scheduled_execution SET last_execution_id = NULL WHERE last_execution_id = :id", id);
        nativeUpdate("DELETE FROM execution WHERE id = :id", id);
        entityManager.clear();
        return label;
    }

    private long count(String jpql, List<UUID> ids) {
        return entityManager.createQuery(jpql, Long.class).setParameter("ids", ids).getSingleResult();
    }

    private void nativeUpdate(String sql, UUID id) {
        entityManager.createNativeQuery(sql).setParameter("id", id).executeUpdate();
    }
}
