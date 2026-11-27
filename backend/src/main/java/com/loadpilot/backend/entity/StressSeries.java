package com.loadpilot.backend.entity;

import com.loadpilot.backend.enums.StressSeriesStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Serie d'essais d'un test de stress : le meme scenario enchaine plusieurs fois, point de rupture consolide. */
@Entity
@Table(name = "stress_series")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StressSeries {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scenario_id", nullable = false, updatable = false)
    private Scenario scenario;

    /** Essais demandes (3 ou 5). */
    @Column(name = "requested_runs", nullable = false, updatable = false)
    private int requestedRuns;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StressSeriesStatus status;

    /** Serie arretee avant la fin : pourquoi. */
    @Column(name = "stop_reason", length = 500)
    private String stopReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "triggered_by_app_user_id", updatable = false)
    private AppUser triggeredBy;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** Pause entre deux essais : lancement prevu de l'essai suivant ; null sinon. */
    @Column(name = "next_run_at")
    private Instant nextRunAt;
}
