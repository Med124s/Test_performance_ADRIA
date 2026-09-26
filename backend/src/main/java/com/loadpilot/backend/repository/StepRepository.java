package com.loadpilot.backend.repository;

import com.loadpilot.backend.entity.Step;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StepRepository extends JpaRepository<Step, UUID> {

    List<Step> findByScenarioId(UUID scenarioId, Sort sort);

    /** Utilise par ScenarioService pour empecher la suppression d'un
     * Scenario ayant encore des Steps rattaches (voir Phase 8). */
    boolean existsByScenarioId(UUID scenarioId);
}
