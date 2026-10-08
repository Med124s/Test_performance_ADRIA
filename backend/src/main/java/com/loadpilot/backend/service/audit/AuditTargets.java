package com.loadpilot.backend.service.audit;

import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.repository.ExecutionRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Noms lisibles des objets pour le journal d'audit, ex. une execution :
 * « Parcours bancaire du 07/10 à 14:32 » (scenario + date de lancement).
 * Le nom est fige au moment de l'action : il reste lisible meme si l'objet
 * est supprime ensuite.
 */
@Component
@RequiredArgsConstructor
public class AuditTargets {

    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("dd/MM 'à' HH:mm").withZone(ZoneId.systemDefault());

    private final ExecutionRepository executionRepository;

    /** « Parcours bancaire du 07/10 à 14:32 » ; sans date : le nom du scenario. */
    public static String executionLabel(String scenarioName, Instant date) {
        String name = scenarioName != null ? scenarioName : "Scénario inconnu";
        return date != null ? name + " du " + DAY_TIME.format(date) : name;
    }

    public static String executionLabel(Execution execution) {
        if (execution == null) {
            return null;
        }
        String scenario = execution.getScenario() != null ? execution.getScenario().getName() : null;
        return executionLabel(scenario, execution.getStartedAt());
    }

    /** Nom lisible d'une execution par son id (lecture seule, sans jamais lever d'exception). */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public String executionLabel(UUID executionId) {
        try {
            return executionRepository.findByIdWithScenarioAndApplication(executionId)
                    .map(AuditTargets::executionLabel)
                    .orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Statut d'execution en francais (jamais le code brut). */
    public static String statusLabel(com.loadpilot.backend.enums.ExecutionStatus status) {
        if (status == null) {
            return "—";
        }
        return switch (status) {
            case QUEUED -> "En attente";
            case RUNNING -> "En cours";
            case PAUSED -> "En pause";
            case SUCCESS -> "Réussie";
            case THRESHOLDS_EXCEEDED -> "Seuils dépassés";
            case FAILED -> "Échouée";
            case CANCELLED -> "Annulée";
        };
    }

    public AuditEvent onExecution(AuditEvent event, UUID executionId) {
        String label = executionLabel(executionId);
        return event.target(AuditTargetType.EXECUTION, executionId, label != null ? label : "Exécution supprimée");
    }
}
