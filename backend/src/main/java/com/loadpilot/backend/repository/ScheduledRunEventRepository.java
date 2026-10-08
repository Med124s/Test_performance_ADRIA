package com.loadpilot.backend.repository;

import com.loadpilot.backend.entity.ScheduledRunEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduledRunEventRepository extends JpaRepository<ScheduledRunEvent, UUID> {

    List<ScheduledRunEvent> findByScheduleIdOrderByPlannedAtDesc(UUID scheduleId, Pageable pageable);
}
