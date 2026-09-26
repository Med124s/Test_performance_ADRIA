package com.loadpilot.backend.repository;

import com.loadpilot.backend.entity.AuditLog;
import com.loadpilot.backend.enums.AuditResult;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * JpaSpecificationExecutor : les filtres combinables de GET /api/audit-logs
 * (userId/username/action/module/result/date range, tous optionnels, logique
 * ET) sont construits dynamiquement via specification.AuditLogSpecifications
 * plutot que via une explosion de methodes derivees findByXxxAndYyy... - le
 * nombre de combinaisons possibles (7 filtres optionnels) rendrait ces
 * dernieres ingerables (voir MetricRepository.search pour un cas plus simple
 * a 4 filtres deja traite en JPQL brut ; ici le package specification dedie,
 * deja prevu par le projet, est le bon outil).
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID>, JpaSpecificationExecutor<AuditLog> {

    long countByResult(AuditResult result);

    @Query("SELECT a.module, COUNT(a) FROM AuditLog a GROUP BY a.module")
    List<Object[]> countGroupedByModule();

    @Query("SELECT a.action, COUNT(a) FROM AuditLog a GROUP BY a.action")
    List<Object[]> countGroupedByAction();
}
