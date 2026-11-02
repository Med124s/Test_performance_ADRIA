package com.loadpilot.backend.repository;

import com.loadpilot.backend.entity.Metric;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Ancienne table metric, plus ecrite ni lue ; ses lignes sont effacees par la purge des resultats. */
public interface MetricRepository extends JpaRepository<Metric, UUID> {
}
