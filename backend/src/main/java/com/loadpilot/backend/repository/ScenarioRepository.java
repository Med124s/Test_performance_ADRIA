package com.loadpilot.backend.repository;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import com.loadpilot.backend.entity.Scenario;
import com.loadpilot.backend.enums.ScenarioStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScenarioRepository extends JpaRepository<Scenario, UUID>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<Scenario> {

    List<Scenario> findByApplicationId(UUID applicationId, Sort sort);

    /** Scenarios visibles : ni archives, ni rattaches a une application archivee. */
    @Query("SELECT s FROM Scenario s JOIN FETCH s.application a WHERE s.archived = false AND a.archived = false")
    List<Scenario> findVisible(Sort sort);

    /** Vue "archives" : scenarios archives OU d'une application archivee. */
    @Query("SELECT s FROM Scenario s JOIN FETCH s.application a WHERE s.archived = true OR a.archived = true")
    List<Scenario> findArchivedView(Sort sort);

    @Query("SELECT s FROM Scenario s JOIN FETCH s.application a WHERE a.id = :applicationId AND s.archived = false AND a.archived = false")
    List<Scenario> findVisibleByApplicationId(@Param("applicationId") UUID applicationId, Sort sort);

    @Query("SELECT s FROM Scenario s JOIN FETCH s.application a WHERE a.id = :applicationId AND (s.archived = true OR a.archived = true)")
    List<Scenario> findArchivedViewByApplicationId(@Param("applicationId") UUID applicationId, Sort sort);

    /** Utilise par ApplicationService pour empecher la suppression d'une
     * Application ayant encore des Scenarios rattaches (voir Phase 7). */
    boolean existsByApplicationId(UUID applicationId);

    // ---- Phase 11 (Dashboard) - agregations, aucun N+1 (une requete COUNT
    // par statistique, jamais un chargement de la collection entiere). ----

    long countByStatus(ScenarioStatus status);

    long countByApplicationId(UUID applicationId);

    long countByApplicationIdAndStatus(UUID applicationId, ScenarioStatus status);
}
