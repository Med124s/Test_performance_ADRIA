package com.loadpilot.backend.repository;

import com.loadpilot.backend.entity.AuditLog;
import com.loadpilot.backend.enums.AuditResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * JpaSpecificationExecutor : les filtres combinables de GET /api/audit-logs
 * sont construits dynamiquement via specification.AuditLogSpecifications.
 *
 * Aucune methode de modification d'une entree : le journal est en ajout
 * seul. Seule suppression possible : la purge automatique des entrees plus
 * anciennes que la duree de conservation (voir AuditRetentionJob).
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID>, JpaSpecificationExecutor<AuditLog> {

    long countByResult(AuditResult result);

    @Query("SELECT a.module, COUNT(a) FROM AuditLog a GROUP BY a.module")
    List<Object[]> countGroupedByModule();

    @Query("SELECT a.action, COUNT(a) FROM AuditLog a GROUP BY a.action")
    List<Object[]> countGroupedByAction();

    boolean existsByActionAndUserIdAndDateAfter(com.loadpilot.backend.enums.AuditAction action, String userId, java.time.Instant after);

    boolean existsByExternalId(String externalId);

    /** Utilisateurs ayant au moins une action (filtre « Utilisateur ») : comptes reels uniquement. */
    @Query("SELECT DISTINCT a.username FROM AuditLog a WHERE a.actorType = 'USER' AND a.username IS NOT NULL AND a.userId IS NOT NULL ORDER BY a.username")
    List<String> findDistinctActorUsernames();

    @Modifying
    @Query("DELETE FROM AuditLog a WHERE a.date < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
