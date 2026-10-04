package com.loadpilot.backend.repository;

import com.loadpilot.backend.entity.ExecutionRequestDetail;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExecutionRequestDetailRepository extends JpaRepository<ExecutionRequestDetail, UUID> {

    @Query("SELECT d FROM ExecutionRequestDetail d WHERE d.result.id = :resultId")
    Optional<ExecutionRequestDetail> findByResultId(@Param("resultId") UUID resultId);

    /** [resultId, kind] des resultats de la page qui ont un detail (indicateur dans la liste). */
    @Query("SELECT d.result.id, d.kind FROM ExecutionRequestDetail d WHERE d.result.id IN :resultIds")
    List<Object[]> findKindsByResultIds(@Param("resultIds") Collection<UUID> resultIds);

    @Query("SELECT COUNT(d) FROM ExecutionRequestDetail d WHERE d.execution.id = :executionId")
    long countByExecutionId(@Param("executionId") UUID executionId);

    @Query("SELECT COUNT(d) FROM ExecutionRequestDetail d WHERE d.execution.id = :executionId AND d.kind = :kind")
    long countByExecutionIdAndKind(@Param("executionId") UUID executionId, @Param("kind") String kind);
}
