package com.loadpilot.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Rapport fige d'une execution terminee (JSON), conserve apres la purge des resultats. */
@Entity
@Table(name = "execution_report_snapshot")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionReportSnapshot {

    @Id
    @Column(name = "execution_id", nullable = false)
    private UUID executionId;

    @Column(name = "snapshot", length = 1000000, nullable = false)
    private String snapshot;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;
}
