package com.loadpilot.backend.repository;

import com.loadpilot.backend.entity.StressSeries;
import com.loadpilot.backend.enums.StressSeriesStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StressSeriesRepository extends JpaRepository<StressSeries, UUID> {

    /** Serie avec scenario, application et auteur deja charges. */
    @Query("SELECT s FROM StressSeries s JOIN FETCH s.scenario sc JOIN FETCH sc.application LEFT JOIN FETCH s.triggeredBy WHERE s.id = :id")
    Optional<StressSeries> findByIdWithScenario(@Param("id") UUID id);

    List<StressSeries> findByStatus(StressSeriesStatus status);
}
