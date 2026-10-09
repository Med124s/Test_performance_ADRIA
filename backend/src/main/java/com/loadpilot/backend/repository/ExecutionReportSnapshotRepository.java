package com.loadpilot.backend.repository;

import com.loadpilot.backend.entity.ExecutionReportSnapshot;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionReportSnapshotRepository extends JpaRepository<ExecutionReportSnapshot, UUID> {
}
