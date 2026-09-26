package com.loadpilot.backend.repository;

import com.loadpilot.backend.entity.Scenario;
import com.loadpilot.backend.enums.ScenarioStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScenarioRepository extends JpaRepository<Scenario, UUID> {

    List<Scenario> findByApplicationId(UUID applicationId, Sort sort);

    /** Utilise par ApplicationService pour empecher la suppression d'une
     * Application ayant encore des Scenarios rattaches (voir Phase 7). */
    boolean existsByApplicationId(UUID applicationId);

    // ---- Phase 11 (Dashboard) - agregations, aucun N+1 (une requete COUNT
    // par statistique, jamais un chargement de la collection entiere). ----

    long countByStatus(ScenarioStatus status);

    long countByApplicationId(UUID applicationId);

    long countByApplicationIdAndStatus(UUID applicationId, ScenarioStatus status);
}
