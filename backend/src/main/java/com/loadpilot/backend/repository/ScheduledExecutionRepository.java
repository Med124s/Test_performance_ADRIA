package com.loadpilot.backend.repository;

import com.loadpilot.backend.entity.ScheduledExecution;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScheduledExecutionRepository extends JpaRepository<ScheduledExecution, UUID> {

    /** Planifications avec scenario, application et createur deja charges. */
    @Query("SELECT s FROM ScheduledExecution s JOIN FETCH s.scenario sc JOIN FETCH sc.application JOIN FETCH s.createdBy ORDER BY s.createdAt DESC")
    List<ScheduledExecution> findAllWithScenarioAndApplication();

    @Query("SELECT s FROM ScheduledExecution s JOIN FETCH s.scenario sc JOIN FETCH sc.application JOIN FETCH s.createdBy WHERE sc.id = :scenarioId ORDER BY s.createdAt DESC")
    List<ScheduledExecution> findByScenarioIdWithScenarioAndApplication(@Param("scenarioId") UUID scenarioId);

    /** Planifications d'un scenario, actives ou non. */
    long countByScenarioId(UUID scenarioId);

    /** Planification avec scenario, application et createur deja charges. */
    @Query("SELECT s FROM ScheduledExecution s JOIN FETCH s.scenario sc JOIN FETCH sc.application JOIN FETCH s.createdBy WHERE s.id = :id")
    Optional<ScheduledExecution> findByIdWithScenarioAndApplication(UUID id);

    /** Identifiants des planifications activees dont le prochain lancement est passe. */
    @Query("SELECT s.id FROM ScheduledExecution s WHERE s.enabled = true AND s.nextRunAt IS NOT NULL AND s.nextRunAt <= :now "
            + "AND s.scenario.status = com.loadpilot.backend.enums.ScenarioStatus.ACTIVE")
    List<UUID> findDueScheduleIds(@Param("now") Instant now);

    /** Planification verrouillee (SELECT ... FOR UPDATE) pendant son declenchement. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ScheduledExecution s WHERE s.id = :id")
    Optional<ScheduledExecution> findByIdForUpdate(@Param("id") UUID id);
}
