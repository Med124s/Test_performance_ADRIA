package com.loadpilot.backend.repository;

import com.loadpilot.backend.entity.Step;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StepRepository extends JpaRepository<Step, UUID> {

    List<Step> findByScenarioId(UUID scenarioId, Sort sort);

    boolean existsByScenarioId(UUID scenarioId);
}
