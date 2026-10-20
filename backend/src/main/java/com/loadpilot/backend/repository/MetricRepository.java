package com.loadpilot.backend.repository;

import com.loadpilot.backend.entity.Metric;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * ANCIENNE table metric, en fin de vie (voir migration 041) : plus aucune ligne n'est ecrite ni lue -
 * les metriques sont calculees par agregats sur execution_step_result (PerformanceAggregates,
 * MetricServiceImpl). Les lignes deja presentes restent jusqu'a leur purge normale a 90 jours
 * (ResultsRetentionJob) ou la suppression de leur execution (DeletionServiceImpl,
 * ExecutionDeletionService), qui continuent de les effacer. Repository garde pour les tests de ces
 * suppressions.
 */
public interface MetricRepository extends JpaRepository<Metric, UUID> {
}
